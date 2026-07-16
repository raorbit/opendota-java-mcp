package com.raorbit.opendota.client;

import com.raorbit.opendota.sidecar.SidecarHttpServer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for {@link SidecarHttpServer}: a fake OpenDota upstream (a JDK
 * {@link HttpServer}) sits behind a real {@link OpenDotaClient}, which the sidecar
 * wraps; the test drives the sidecar over real HTTP as a forwarding agent would.
 *
 * <p>The test lives in the {@code client} package so it can build the client with
 * the package-private base-URL constructor (pointing it at the fake upstream rather
 * than the real api.opendota.com).
 */
class SidecarHttpServerTest {

    private HttpServer upstream;
    private SidecarHttpServer sidecar;
    private HttpClient http;
    private String sidecarBase;
    private String upstreamBase;
    private final AtomicInteger upstreamHits = new AtomicInteger();
    /** Request targets (path + raw query) the fake upstream received, in order. */
    private final List<String> upstreamReceived = new CopyOnWriteArrayList<>();
    /** HTTP methods the fake upstream received, in order (parallel to {@link #upstreamReceived}). */
    private final List<String> upstreamMethods = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.setExecutor(Executors.newCachedThreadPool());
        upstream.start();
        upstreamBase = "http://localhost:" + upstream.getAddress().getPort() + "/api";

        // The sidecar's single shared client, pointed at the fake upstream.
        OpenDotaClient client = new OpenDotaClient(null, upstreamBase);
        sidecar = new SidecarHttpServer(0, client);
        sidecar.start();
        sidecarBase = "http://127.0.0.1:" + sidecar.port();
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (http != null) {
            http.close();
        }
        if (sidecar != null) {
            sidecar.close();
        }
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    /** Register a fixed upstream response at an exact /api/... path, counting hits. */
    private void stubUpstream(String apiPath, int status, String body) {
        upstream.createContext(apiPath, exchange -> {
            upstreamHits.incrementAndGet();
            String target = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            upstreamReceived.add(query == null ? target : target + "?" + query);
            upstreamMethods.add(exchange.getRequestMethod());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                if (bytes.length > 0) {
                    os.write(bytes);
                }
            }
        });
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(sidecarBase + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String base, String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Send a raw HTTP/1.1 request with an explicit request-target and {@code Host} header, returning the
     * response status code. Needed because the JDK {@link HttpClient} normalizes {@code ..} out of the
     * path and forbids setting the {@code Host} header — both of which these guards must be tested against.
     */
    private int rawRequest(String method, String target, String hostHeader) throws IOException {
        return rawRequest(sidecar.port(), method, target, hostHeader);
    }

    /**
     * As {@link #rawRequest(String, String, String)} but against an explicit port and with optional extra
     * header lines (e.g. {@code "Origin: https://evil.example"}), so the browser-only forbidden headers
     * the cross-origin guard keys on can be sent exactly as a browser would.
     */
    private static int rawRequest(int port, String method, String target, String hostHeader,
            String... extraHeaders) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            socket.setSoTimeout(2000);
            StringBuilder request = new StringBuilder()
                    .append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(hostHeader).append("\r\n");
            for (String header : extraHeaders) {
                request.append(header).append("\r\n");
            }
            if ("POST".equals(method)) {
                request.append("Content-Length: 0\r\n");
            }
            request.append("Connection: close\r\n\r\n");
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = in.readLine();   // e.g. "HTTP/1.1 403 Forbidden"
            if (statusLine == null) {
                return -1;
            }
            String[] parts = statusLine.split(" ");
            return parts.length >= 2 ? Integer.parseInt(parts[1]) : -1;
        }
    }

    @Test
    void nonLoopbackHostHeaderIsRejectedWhenNoTokenSet() throws Exception {
        // The default sidecar has no token: a request whose Host is NOT a loopback name — as a
        // DNS-rebinding page sends (its own hostname) — is refused with 403 before reaching the shared
        // client/upstream, while a genuine loopback Host is served. Guards against DNS rebinding of the
        // token-less loopback listener.
        stubUpstream("/api/heroes", 200, "[]");
        // A spoofed (non-loopback) Host is refused with 403 and never reaches the upstream...
        assertThat(rawRequest("GET", "/api/heroes", "evil.example.com")).isEqualTo(403);
        assertThat(upstreamHits.get()).isZero();
        // ...while genuine loopback Hosts are served — including the bracketed IPv6 literal forms,
        // pinning both the "::1" LOOPBACK_HOSTS membership and hostOnly()'s bracket/port stripping.
        assertThat(rawRequest("GET", "/api/heroes", "127.0.0.1:" + sidecar.port())).isEqualTo(200);
        assertThat(rawRequest("GET", "/api/heroes", "localhost")).isEqualTo(200);
        assertThat(rawRequest("GET", "/api/heroes", "[::1]:" + sidecar.port())).isEqualTo(200);
        assertThat(rawRequest("GET", "/api/heroes", "[::1]")).isEqualTo(200);
        // Any literal 127/8 address is loopback (requiresToken lets the sidecar BIND any of them
        // token-less, so its own agents' Host headers must be accepted)...
        assertThat(rawRequest("GET", "/api/heroes", "127.0.0.2:" + sidecar.port())).isEqualTo(200);
        // ...but only as a full dotted-quad literal — hostNAMES under a 127. label stay rejected.
        assertThat(rawRequest("GET", "/api/heroes", "127.evil.example.com")).isEqualTo(403);
        assertThat(rawRequest("GET", "/api/heroes", "127.0.0.1.evil.example.com")).isEqualTo(403);
        // An UNbracketed IPv6 Host is malformed per RFC 7230 (the port separator is ambiguous), so it
        // is rejected rather than special-cased.
        assertThat(rawRequest("GET", "/api/heroes", "::1")).isEqualTo(403);
        // /heroes is cacheable, so the allowed reads collapse to a single upstream fetch.
        assertThat(upstreamHits.get()).isEqualTo(1);
    }

    @Test
    void browserCrossOriginRequestsAreRejectedWhenNoTokenSet() throws Exception {
        // A malicious page can fire fetch(..., {mode:'no-cors'}) straight at http://127.0.0.1:<port>
        // with a perfectly legitimate loopback Host header, so the anti-rebinding Host guard alone
        // cannot stop it — most damagingly on the POST write path, which queues parse jobs under the
        // sidecar's shared API key. Browsers stamp such requests with forbidden headers page script
        // cannot strip (Sec-Fetch-Site, Origin); the token-less sidecar must refuse them.
        stubUpstream("/api/heroes", 200, "[]");
        stubUpstream("/api/request/123", 200, "{\"job\":{\"jobId\":42}}");
        String host = "127.0.0.1:" + sidecar.port();

        // Cross-site browser traffic is refused, on reads and writes alike...
        assertThat(rawRequest(sidecar.port(), "GET", "/api/heroes", host,
                "Sec-Fetch-Site: cross-site")).isEqualTo(403);
        assertThat(rawRequest(sidecar.port(), "POST", "/api/request/123", host,
                "Sec-Fetch-Site: cross-site", "Origin: https://evil.example")).isEqualTo(403);
        // ...including same-site (another localhost port) and an Origin-only request from an older
        // browser without fetch metadata. /stats is guarded the same way.
        assertThat(rawRequest(sidecar.port(), "GET", "/api/heroes", host,
                "Sec-Fetch-Site: same-site")).isEqualTo(403);
        assertThat(rawRequest(sidecar.port(), "POST", "/api/request/123", host,
                "Origin: https://evil.example")).isEqualTo(403);
        assertThat(rawRequest(sidecar.port(), "GET", "/stats", host,
                "Sec-Fetch-Site: cross-site")).isEqualTo(403);
        assertThat(upstreamHits.get()).isZero();

        // Non-browser agent clients (no fetch-metadata headers) and a user-typed navigation still pass.
        assertThat(rawRequest("GET", "/api/heroes", host)).isEqualTo(200);
        assertThat(rawRequest(sidecar.port(), "GET", "/api/heroes", host,
                "Sec-Fetch-Site: none")).isEqualTo(200);
    }

    @Test
    void tokenGatedSidecarSkipsTheCrossOriginGuard() throws Exception {
        // With a shared secret configured the token itself is the CSRF defense (a hostile page cannot
        // know it, and attaching the header would force a CORS preflight the sidecar never approves),
        // so browser-shaped traffic presenting a valid token — e.g. a trusted local dashboard — is served.
        stubUpstream("/api/heroes", 200, "[]");
        try (SidecarHttpServer gated = new SidecarHttpServer(0, new OpenDotaClient(null, upstreamBase), "s3cret")) {
            gated.start();
            assertThat(rawRequest(gated.port(), "GET", "/api/heroes", "127.0.0.1:" + gated.port(),
                    "Sec-Fetch-Site: cross-site", "Origin: http://localhost:3000",
                    "X-Sidecar-Token: s3cret")).isEqualTo(200);
        }
    }

    @Test
    void dotSegmentPathsAreRejected() throws Exception {
        // A raw '..' segment must be rejected rather than forwarded to OpenDota unnormalized (the JDK
        // HttpClient would strip it, so it is sent over a socket). Never reaches the upstream.
        stubUpstream("/api/records", 200, "[]");
        // Catch-all context so ANY forwarded request — even one no stub matches — counts as an
        // upstream hit. Without it, forwarded-but-unmatched gets the server's built-in 404 with
        // upstreamHits untouched, indistinguishable from the guard rejecting the path — i.e. these
        // assertions would stay green even with the traversal guard deleted.
        upstream.createContext("/", exchange -> {
            upstreamHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        String host = "127.0.0.1:" + sidecar.port();
        assertThat(rawRequest("GET", "/api/../records", host)).isEqualTo(404);
        assertThat(rawRequest("GET", "/api/players/../../records", host)).isEqualTo(404);
        // Percent-encoded traversal must be rejected too: %2e%2e (lower/upper) and encoded separators
        // (%2f) would otherwise pass a literal ".." check and be sent verbatim to a normalizing upstream.
        assertThat(rawRequest("GET", "/api/players/%2e%2e/%2e%2e/admin", host)).isEqualTo(404);
        assertThat(rawRequest("GET", "/api/players/%2E%2E/records", host)).isEqualTo(404);
        assertThat(rawRequest("GET", "/api/players/..%2f..%2fadmin", host)).isEqualTo(404);
        // The encoded backslash separator (Windows-style traversal a normalizing upstream could
        // collapse) and the encoded percent (which would smuggle a DOUBLE-encoded traversal through
        // one round of upstream decoding) are rejected too, upper- and lower-case.
        assertThat(rawRequest("GET", "/api/players/..%5c..%5cadmin", host)).isEqualTo(404);
        assertThat(rawRequest("GET", "/api/players/..%5C..%5Cadmin", host)).isEqualTo(404);
        assertThat(rawRequest("GET", "/api/players/%252e%252e/admin", host)).isEqualTo(404);
        assertThat(upstreamHits.get()).isZero();
    }

    @Test
    void benignPercentEncodedSegmentIsForwardedNotRejected() throws Exception {
        // The traversal guard must reject only encoded dot-segments/separators, NOT ordinary
        // percent-encoding (e.g. get_constants of a non-ASCII resource). Such a request must forward
        // verbatim — behaving like a direct client — rather than being 404'd by the sidecar. %6F -> 'o'.
        stubUpstream("/api/constants", 200, "{}");   // prefix context; matches /api/constants/...
        HttpResponse<String> r = get("/api/constants/fo%6F");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(upstreamReceived).containsExactly("/api/constants/fo%6F");
    }

    @Test
    void healthReturnsOk() throws Exception {
        HttpResponse<String> r = get("/health");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("ok").contains("\"version\":");
    }

    @Test
    void apiRequestIsProxiedAndBodyReturnedVerbatim() throws Exception {
        String body = "[{\"id\":1,\"name\":\"npc_dota_hero_antimage\"}]";
        stubUpstream("/api/heroes", 200, body);

        HttpResponse<String> r = get("/api/heroes");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo(body);
        assertThat(upstreamReceived).containsExactly("/api/heroes");
    }

    @Test
    void queryStringIsForwardedToUpstream() throws Exception {
        stubUpstream("/api/proMatches", 200, "[]");

        HttpResponse<String> r = get("/api/proMatches?less_than_match_id=5");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(upstreamReceived).containsExactly("/api/proMatches?less_than_match_id=5");
    }

    @Test
    void callerSuppliedApiKeyIsStrippedBeforeForwarding() throws Exception {
        // The sidecar owns the API key; a caller-supplied api_key param must never ride the shared
        // hop (on a keyed sidecar it would be forwarded as a duplicate parameter next to the real one).
        stubUpstream("/api/heroes", 200, "[]");
        stubUpstream("/api/proMatches", 200, "[]");

        assertThat(get("/api/heroes?api_key=EVIL&foo=1").statusCode()).isEqualTo(200);
        // Only api_key in the query -> forwarded with no query at all (no dangling '?').
        assertThat(get("/api/proMatches?api_key=EVIL").statusCode()).isEqualTo(200);

        assertThat(upstreamReceived).containsExactly("/api/heroes?foo=1", "/api/proMatches");
    }

    @Test
    void percentEncodedApiKeySpellingIsStrippedToo() throws Exception {
        // %61pi_key decodes to api_key: an encoded NAME must not slip past the strip and reach a
        // decoding upstream as a second api_key parameter.
        stubUpstream("/api/heroes", 200, "[]");

        assertThat(get("/api/heroes?%61pi_key=EVIL&foo=1").statusCode()).isEqualTo(200);

        assertThat(upstreamReceived).containsExactly("/api/heroes?foo=1");
    }

    @Test
    void keyedSidecarForwardsOnlyItsOwnApiKey() throws Exception {
        String sidecarKey = "00000000-1111-2222-3333-444444444444";
        stubUpstream("/api/heroes", 200, "[]");
        try (SidecarHttpServer keyed = new SidecarHttpServer(0, new OpenDotaClient(sidecarKey, upstreamBase))) {
            keyed.start();
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + keyed.port()
                            + "/api/heroes?api_key=EVIL")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(200);
        }
        // Exactly one api_key reached the upstream — the sidecar's, not the caller's.
        assertThat(upstreamReceived).hasSize(1);
        assertThat(upstreamReceived.get(0))
                .contains("api_key=" + sidecarKey)
                .doesNotContain("EVIL");
        assertThat(upstreamReceived.get(0).indexOf("api_key"))
                .isEqualTo(upstreamReceived.get(0).lastIndexOf("api_key"));
    }

    @Test
    void upstreamErrorStatusIsMirrored() throws Exception {
        stubUpstream("/api/players/999", 404, "{\"error\":\"Not Found\"}");

        HttpResponse<String> r = get("/api/players/999");

        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).contains("Not Found");
    }

    @Test
    void unknownPathOutsideApiPrefixIs404() throws Exception {
        HttpResponse<String> r = get("/nope");
        assertThat(r.statusCode()).isEqualTo(404);
    }

    @Test
    void concurrentIdenticalRequestsHitUpstreamOnce() throws Exception {
        // Single-flight through the sidecar: concurrent identical GETs collapse to one
        // upstream call. /players/* is cacheable, so followers await the one leader.
        String body = "{\"profile\":{\"account_id\":123}}";
        CountDownLatch release = new CountDownLatch(1);
        upstream.createContext("/api/players/123", exchange -> {
            upstreamHits.incrementAndGet();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return get("/api/players/123");
            }));
        }
        start.countDown();
        Thread.sleep(250);   // let all callers reach the in-flight wait
        release.countDown();

        for (Future<HttpResponse<String>> f : futures) {
            HttpResponse<String> r = f.get(5, TimeUnit.SECONDS);
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.body()).isEqualTo(body);
        }
        pool.shutdownNow();

        assertThat(upstreamHits.get()).isEqualTo(1);
    }

    @Test
    void unsupportedMethodIsRejectedWith405() throws Exception {
        // GET and POST are both forwarded; any other verb (e.g. DELETE) is refused, as is any
        // non-GET to /health (GET-only). The verb is rejected before reaching the shared client.
        stubUpstream("/api/heroes", 200, "[]");

        HttpResponse<String> health = post(sidecarBase, "/health");
        HttpResponse<String> api = http.send(
                HttpRequest.newBuilder(URI.create(sidecarBase + "/api/heroes")).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(health.statusCode()).isEqualTo(405);
        assertThat(api.statusCode()).isEqualTo(405);
        // The verb is rejected before reaching the shared client / upstream.
        assertThat(upstreamHits.get()).isEqualTo(0);
    }

    @Test
    void postIsForwardedToUpstream() throws Exception {
        // A parse request: POST /api/request/123 maps to OpenDota POST /request/123. The sidecar
        // forwards it (via the client's POST path) and returns the body.
        stubUpstream("/api/request/123", 200, "{\"job\":{\"jobId\":42}}");

        HttpResponse<String> r = post(sidecarBase, "/api/request/123");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("{\"job\":{\"jobId\":42}}");
        assertThat(upstreamReceived).containsExactly("/api/request/123");
        // It really reached the upstream as a POST, not a GET.
        assertThat(upstreamMethods).containsExactly("POST");
    }

    @Test
    void postUpstreamErrorIsMirrored() throws Exception {
        // A write whose upstream rejects it (e.g. a bad match id) mirrors the status and body, just
        // like the GET path does.
        stubUpstream("/api/request/0", 400, "{\"error\":\"bad request\"}");

        HttpResponse<String> r = post(sidecarBase, "/api/request/0");

        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("bad request");
    }

    @Test
    void writesDisabledRejectsPostButStillProxiesReads() throws Exception {
        // A read-only sidecar (OPENDOTA_SIDECAR_ALLOW_WRITES=false) refuses the write with 403 and never
        // spends the API key on it, while GET reads pass through unchanged.
        stubUpstream("/api/request/123", 200, "{\"job\":{\"jobId\":42}}");
        stubUpstream("/api/heroes", 200, "[]");

        OpenDotaClient client = new OpenDotaClient(null, upstreamBase);
        try (SidecarHttpServer readOnly = new SidecarHttpServer("127.0.0.1", 0, client, null, null, false)) {
            readOnly.start();
            String base = "http://127.0.0.1:" + readOnly.port();

            HttpResponse<String> write = post(base, "/api/request/123");
            assertThat(write.statusCode()).isEqualTo(403);
            assertThat(write.body()).contains("writes disabled");

            HttpResponse<String> read = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/heroes")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(read.statusCode()).isEqualTo(200);
        }
        // The blocked write never reached the upstream; only the read did.
        assertThat(upstreamMethods).containsExactly("GET");
        assertThat(upstreamReceived).containsExactly("/api/heroes");
    }

    @Test
    void postIsTokenGatedLikeGet() throws Exception {
        // A token-gated sidecar requires the shared secret on the POST, just as it does on a GET.
        stubUpstream("/api/players/123/refresh", 200, "ok");

        try (SidecarHttpServer gated = new SidecarHttpServer(0, new OpenDotaClient(null, upstreamBase), "s3cret")) {
            gated.start();
            String base = "http://127.0.0.1:" + gated.port();

            // No token -> 401, never reaches the upstream.
            HttpResponse<String> missing = post(base, "/api/players/123/refresh");
            assertThat(missing.statusCode()).isEqualTo(401);
            assertThat(upstreamHits.get()).isEqualTo(0);

            // Correct token -> forwarded.
            HttpResponse<String> ok = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/players/123/refresh"))
                            .header("X-Sidecar-Token", "s3cret")
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(ok.statusCode()).isEqualTo(200);
            assertThat(upstreamMethods).containsExactly("POST");
        }
    }

    @Test
    void upstreamTransportFailureBecomes502() throws Exception {
        // The wrapped client cannot reach its upstream (a closed port) -> OpenDotaException
        // with statusCode 0 -> the sidecar maps that transport failure to HTTP 502.
        int deadPort;
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            deadPort = s.getLocalPort();
        }   // released here — nothing listens on deadPort
        OpenDotaClient deadClient = new OpenDotaClient(null, "http://127.0.0.1:" + deadPort + "/api");

        try (SidecarHttpServer dead = new SidecarHttpServer(0, deadClient)) {
            dead.start();
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + dead.port() + "/api/heroes")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(502);
        }
    }

    @Test
    void apiKeyIsRedactedInMirroredErrorBodyEndToEnd() throws Exception {
        // The highest-stakes path: a keyed sidecar + a hostile upstream that echoes the
        // api_key in its error body. The mirrored body that crosses the loopback hop to the
        // agent must be redacted, never the raw secret.
        String apiKey = "00000000-1111-2222-3333-444444444444";
        upstream.createContext("/api/players/777", exchange -> {
            upstreamHits.incrementAndGet();
            String target = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();   // carries ?api_key=<key>
            String echoed = "{\"error\":\"denied " + (query == null ? target : target + "?" + query) + "\"}";
            byte[] bytes = echoed.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        OpenDotaClient keyedClient = new OpenDotaClient(apiKey, upstreamBase);
        try (SidecarHttpServer keyed = new SidecarHttpServer(0, keyedClient)) {
            keyed.start();
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + keyed.port() + "/api/players/777"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(r.statusCode()).isEqualTo(500);
            assertThat(r.body()).contains("REDACTED").doesNotContain(apiKey);
        }
    }

    @Test
    void prefixPathsOutsideTheExactContextAre404() throws Exception {
        // JDK HttpServer matches contexts by prefix; /stats and /health must reject longer paths
        // (e.g. /statsZZZ, /healthz) rather than serve them from those handlers.
        assertThat(get("/statsZZZ").statusCode()).isEqualTo(404);
        assertThat(get("/healthz").statusCode()).isEqualTo(404);
    }

    @Test
    void statsReportsCacheAndLimiterCounters() throws Exception {
        stubUpstream("/api/players/123", 200, "{\"profile\":{\"account_id\":123}}");

        // First call misses the L1 cache and fetches; the second is served from cache (a hit).
        get("/api/players/123");
        get("/api/players/123");

        HttpResponse<String> stats = get("/stats");
        assertThat(stats.statusCode()).isEqualTo(200);
        assertThat(stats.body())
                .contains("\"cacheHits\":")
                .contains("\"cacheMisses\":")
                .contains("\"cacheEntries\":")
                .contains("\"availablePermits\":")
                // keyless tier default budget, and at least one cache hit from the repeat call.
                .contains("\"permitsPerMinute\":60")
                .contains("\"cacheHits\":1");
        // One upstream fetch despite two identical calls (cache hit on the second).
        assertThat(upstreamHits.get()).isEqualTo(1);
    }

    @Test
    void statsExposesL2WatchedArchiveFieldsWhenL2Enabled(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws Exception {
        // A parsed match mentioning the watched account_id, so fetching it pins one archive row.
        String watchedMatch = "{\"match_id\":777,\"version\":21,\"od_data\":{\"has_parsed\":true},"
                + "\"objectives\":[{\"type\":\"tower\"}],\"players\":[{\"account_id\":12345}]}";
        stubUpstream("/api/matches/777", 200, watchedMatch);

        com.raorbit.opendota.sidecar.L2Config cfg = new com.raorbit.opendota.sidecar.L2Config(
                tmp.resolve("l2.db"), 50_000, 512L * 1024 * 1024, java.time.Duration.ofHours(6).toMillis(),
                null, 4, new com.raorbit.opendota.sidecar.L2Config.Watched(java.util.Set.of(12345L), 0, 0));
        com.raorbit.opendota.sidecar.L2Store store = new com.raorbit.opendota.sidecar.L2Store(
                cfg.dbPath(), com.raorbit.opendota.sidecar.L2Store.SCHEMA_VERSION, cfg.readPoolSize());
        OpenDotaClient client = new OpenDotaClient(null, upstreamBase);
        com.raorbit.opendota.sidecar.L2CachingGateway gw =
                new com.raorbit.opendota.sidecar.L2CachingGateway(client, store, cfg);

        try (SidecarHttpServer s = new SidecarHttpServer("127.0.0.1", 0, client, gw, null)) {
            s.start();
            String base = "http://127.0.0.1:" + s.port();
            // Fetch the watched match through the sidecar to populate the archive.
            http.send(HttpRequest.newBuilder(URI.create(base + "/api/matches/777")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            HttpResponse<String> stats = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/stats")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(stats.statusCode()).isEqualTo(200);
            // The three watched-archive fields actually reach the serialized /stats body (a typo or
            // dropped append in handleStats would fail here).
            assertThat(stats.body())
                    .contains("\"l2Enabled\":true")
                    .contains("\"l2WatchedStore\":1")
                    .contains("\"pinnedRows\":1")
                    .contains("\"pinnedBytes\":" + watchedMatch.getBytes(StandardCharsets.UTF_8).length);
        }
    }

    @Test
    void statsIsTokenGatedLikeApi() throws Exception {
        // /stats exposes operational counters (and confirms a sidecar lives here) — on a
        // token-configured sidecar it must demand the same shared secret as /api.
        try (SidecarHttpServer gated = new SidecarHttpServer(0, new OpenDotaClient(null, upstreamBase), "s3cret")) {
            gated.start();
            String b = "http://127.0.0.1:" + gated.port();

            HttpResponse<String> missing = http.send(
                    HttpRequest.newBuilder(URI.create(b + "/stats")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(missing.statusCode()).isEqualTo(401);

            HttpResponse<String> wrong = http.send(
                    HttpRequest.newBuilder(URI.create(b + "/stats"))
                            .header("X-Sidecar-Token", "nope").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(wrong.statusCode()).isEqualTo(401);

            HttpResponse<String> ok = http.send(
                    HttpRequest.newBuilder(URI.create(b + "/stats"))
                            .header("X-Sidecar-Token", "s3cret").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(ok.statusCode()).isEqualTo(200);
            assertThat(ok.body()).contains("\"cacheHits\":");
        }
    }

    @Test
    void tokenGatedSidecarRequiresMatchingHeaderForApiButNotHealth() throws Exception {
        stubUpstream("/api/heroes", 200, "[]");
        try (SidecarHttpServer gated = new SidecarHttpServer(0, new OpenDotaClient(null, upstreamBase), "s3cret")) {
            gated.start();
            String b = "http://127.0.0.1:" + gated.port();

            // No token header -> 401, and the request never reaches the upstream.
            HttpResponse<String> missing = http.send(
                    HttpRequest.newBuilder(URI.create(b + "/api/heroes")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(missing.statusCode()).isEqualTo(401);

            // Wrong token -> 401.
            HttpResponse<String> wrong = http.send(
                    HttpRequest.newBuilder(URI.create(b + "/api/heroes"))
                            .header("X-Sidecar-Token", "nope").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(wrong.statusCode()).isEqualTo(401);

            // Correct token -> 200 and the body is served.
            HttpResponse<String> ok = http.send(
                    HttpRequest.newBuilder(URI.create(b + "/api/heroes"))
                            .header("X-Sidecar-Token", "s3cret").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(ok.statusCode()).isEqualTo(200);
            assertThat(ok.body()).isEqualTo("[]");

            // /health stays open even when auth is enabled (no token needed).
            HttpResponse<String> health = http.send(
                    HttpRequest.newBuilder(URI.create(b + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);

            // Only the authorized /api call reached the upstream.
            assertThat(upstreamHits.get()).isEqualTo(1);
        }
    }
}

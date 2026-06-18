package com.raorbit.opendota.client;

import com.raorbit.opendota.sidecar.SidecarHttpServer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
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
    private final AtomicInteger upstreamHits = new AtomicInteger();
    /** Request targets (path + raw query) the fake upstream received, in order. */
    private final List<String> upstreamReceived = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.setExecutor(Executors.newCachedThreadPool());
        upstream.start();
        String upstreamBase = "http://localhost:" + upstream.getAddress().getPort() + "/api";

        // The sidecar's single shared client, pointed at the fake upstream.
        OpenDotaClient client = new OpenDotaClient(null, upstreamBase);
        sidecar = new SidecarHttpServer(0, client);
        sidecar.start();
        sidecarBase = "http://127.0.0.1:" + sidecar.port();
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
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

    @Test
    void healthReturnsOk() throws Exception {
        HttpResponse<String> r = get("/health");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("ok");
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
}

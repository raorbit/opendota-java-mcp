package com.raorbit.opendota.sidecar;

import com.raorbit.opendota.client.OpenDotaClient;
import com.raorbit.opendota.client.OpenDotaException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * An HTTP front end for a single shared {@link OpenDotaClient}.
 *
 * <p>Several MCP server processes on one machine each forward their OpenDota GETs
 * here, so the one rate limiter, cache and single-flight in the wrapped client are
 * shared across all of them and OpenDota's real per-key budget is honoured exactly
 * once. The server binds {@code 127.0.0.1} (loopback) by default, so it normally carries
 * no network-reachable surface and the API key it holds never leaves this machine. The
 * bind host is configurable: set {@code 0.0.0.0} only when the sidecar must be reached
 * across a container or network boundary, and gate it with a shared-secret token then.
 *
 * <p>Request contract: {@code GET /api/<openDotaPath>[?query]} maps 1:1 to the
 * OpenDota path {@code /<openDotaPath>[?query]} — mirroring the real base shape, so
 * a forwarding client only has to retarget its base URL. A 2xx returns the raw JSON
 * body verbatim; an upstream error is mirrored as that status with the client's
 * already-redacted, length-bounded error body (a transport failure, status {@code 0},
 * becomes {@code 502}). {@code GET /health} returns {@code 200}.
 *
 * <p>{@code POST /api/*} is forwarded to {@link OpenDotaClient#postJson} — the same rate-limited,
 * never-cached write path the direct server uses — so a forwarding agent's opt-in write tools (parse
 * requests / player refreshes) reach OpenDota under the sidecar's shared key. Writes always bypass the
 * L2 durable cache (it is a GET accelerator) and go straight to the wrapped client. Any verb other than
 * {@code GET}/{@code POST} is refused with {@code 405}.
 *
 * <p>Handlers run on a virtual-thread-per-request executor so concurrent identical
 * requests reach the shared client at once and its single-flight can collapse them
 * into one upstream call.
 */
public final class SidecarHttpServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(SidecarHttpServer.class.getName());
    private static final String API_PREFIX = "/api";
    /** Request header carrying the optional shared secret an auth-gated sidecar requires. */
    private static final String TOKEN_HEADER = "X-Sidecar-Token";
    /**
     * Literal loopback hosts accepted in the {@code Host} header when no token is configured, as an
     * anti-DNS-rebinding guard (see {@link #hostAllowed}). Compared as literals, never DNS-resolved.
     */
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1");
    /**
     * Coarse wire-contract version, surfaced on {@code /health} and {@code /stats} so an operator can spot
     * a sidecar running a different build than its agents. Bump only on a wire-contract change.
     */
    private static final int CONTRACT_VERSION = 1;

    private final HttpServer server;
    private final OpenDotaClient client;
    /**
     * Optional durable L2 decorator wrapping {@link #client}. When non-null, {@code /api} fetches go
     * through {@code gateway.get(path)} (L2 then the client); when null, straight through the bare
     * client, exactly as before. The gateway owns closing both the store and the client.
     */
    private final L2CachingGateway gateway;
    /** Shared secret required on {@code /api/*}, or {@code null} to accept any local caller. */
    private final String token;
    /** Host/interface the server is bound to (default {@code 127.0.0.1}); surfaced in the startup log. */
    private final String bindHost;
    /** Whether inbound {@code POST}s (parse/refresh writes) are forwarded; false makes the sidecar read-only. */
    private final boolean allowWrites;

    /**
     * @param port   loopback port to bind, or {@code 0} to pick an ephemeral one (tests)
     * @param client the shared upstream client; this server takes ownership and closes it
     */
    public SidecarHttpServer(int port, OpenDotaClient client) throws IOException {
        this(port, client, null);
    }

    /**
     * @param port   loopback port to bind, or {@code 0} to pick an ephemeral one (tests)
     * @param client the shared upstream client; this server takes ownership and closes it
     * @param token  optional shared secret; when non-blank, {@code /api/*} requires a matching
     *               {@value #TOKEN_HEADER} header (constant-time compared). {@code /health} stays open.
     */
    public SidecarHttpServer(int port, OpenDotaClient client, String token) throws IOException {
        this("127.0.0.1", port, client, null, token);
    }

    /**
     * @param bindHost host/interface to bind: {@code 127.0.0.1} for loopback-only (the default), or
     *                 {@code 0.0.0.0} to accept connections across a container/network boundary
     * @param port    port to bind, or {@code 0} to pick an ephemeral one (tests)
     * @param client  the shared upstream client (used for {@code /stats} and the write path); when
     *                {@code gateway} is non-null the gateway owns closing it, else this server does
     * @param gateway optional durable L2 decorator; when non-null, {@code /api} GETs route through
     *                it instead of the bare client, and it owns closing the client + SQLite store
     *                (POSTs always go straight to the client, never through L2)
     * @param token   optional shared secret gating {@code /api} and {@code /stats}
     */
    public SidecarHttpServer(String bindHost, int port, OpenDotaClient client, L2CachingGateway gateway,
            String token) throws IOException {
        this(bindHost, port, client, gateway, token, true);
    }

    /**
     * @param allowWrites whether inbound {@code POST}s are forwarded (parse/refresh writes). When
     *                    {@code false} the inbound write path is rejected with {@code 403} — a hardening
     *                    lever for shared/untrusted hosts, matching the direct build's
     *                    {@code opendota.write-tools-enabled} being off by default. This gates only
     *                    inbound HTTP writes; the watched-player auto-parser writes on its own initiative
     *                    and is governed separately by {@code OPENDOTA_SIDECAR_L2_WATCHED_AUTO_PARSE}.
     */
    public SidecarHttpServer(String bindHost, int port, OpenDotaClient client, L2CachingGateway gateway,
            String token, boolean allowWrites) throws IOException {
        this.client = client;
        this.gateway = gateway;
        String trimmed = token == null ? null : token.trim();
        this.token = (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
        this.bindHost = bindHost;
        this.allowWrites = allowWrites;
        this.server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        this.server.createContext("/health", this::handleHealth);
        this.server.createContext("/stats", this::handleStats);
        this.server.createContext(API_PREFIX, this::handleApi);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    public void start() {
        server.start();
        LOG.info(() -> "opendota-sidecar listening on http://" + bindHost + ":" + port()
                + " (keyed=" + client.isKeyed() + ", auth=" + (token != null) + ", l2=" + (gateway != null)
                + ", writes=" + (allowWrites ? "on" : "off") + ")");
    }

    /** The bound port (useful when constructed with port {@code 0}). */
    public int port() {
        return server.getAddress().getPort();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        // The JDK HttpServer matches contexts by path PREFIX, so reject anything but the exact path
        // (e.g. /healthz) rather than serving it from this handler.
        if (!"/health".equals(exchange.getRequestURI().getPath())) {
            respond(exchange, 404, "{\"error\":\"not found\"}");
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        respond(exchange, 200, "{\"status\":\"ok\",\"version\":" + CONTRACT_VERSION + "}");
    }

    /**
     * {@code GET /stats} — the shared client's cache/limiter counters as hand-rolled JSON, so an
     * operator can see how close to the rate budget the sidecar is and whether the cache is earning
     * its keep. Token-gated like {@code /api} when a secret is configured.
     */
    private void handleStats(HttpExchange exchange) throws IOException {
        try {
            // Context matching is by prefix, so reject anything but the exact /stats path.
            if (!"/stats".equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 404, "{\"error\":\"not found\"}");
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            if (!hostAllowed(exchange)) {
                respond(exchange, 403, "{\"error\":\"forbidden\"}");
                return;
            }
            if (!authorized(exchange)) {
                respond(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            OpenDotaClient.Stats s = client.stats();
            StringBuilder json = new StringBuilder("{\"keyed\":").append(s.keyed())
                    .append(",\"cacheHits\":").append(s.cacheHits())
                    .append(",\"cacheMisses\":").append(s.cacheMisses())
                    .append(",\"cacheEntries\":").append(s.cacheEntries())
                    .append(",\"cacheBytes\":").append(s.cacheBytes())
                    .append(",\"availablePermits\":").append(s.availablePermits())
                    .append(",\"permitsPerMinute\":").append(s.permitsPerMinute())
                    .append(",\"version\":").append(CONTRACT_VERSION);
            // Additively expose the L2 counters when the durable tier is enabled (the existing fields
            // above are unchanged, so statsReportsCacheAndLimiterCounters keeps passing).
            if (gateway != null) {
                L2CachingGateway.L2Stats l2 = gateway.stats();
                json.append(",\"l2Enabled\":true")
                        .append(",\"l2Hit\":").append(l2.l2Hit())
                        .append(",\"l2Miss\":").append(l2.l2Miss())
                        .append(",\"l2Store\":").append(l2.l2Store())
                        .append(",\"l2WatchedStore\":").append(l2.l2WatchedStore())
                        .append(",\"l2StoreSkippedUnparsed\":").append(l2.l2StoreSkippedUnparsed())
                        .append(",\"l2PatchBust\":").append(l2.l2PatchBust())
                        .append(",\"l2Error\":").append(l2.l2Error())
                        .append(",\"noStore\":").append(l2.noStore())
                        .append(",\"pinnedRows\":").append(l2.pinnedRows())
                        .append(",\"pinnedBytes\":").append(l2.pinnedBytes())
                        .append(",\"parseRequested\":").append(l2.parseRequested())
                        .append(",\"parseErrors\":").append(l2.parseErrors());
            } else {
                json.append(",\"l2Enabled\":false");
            }
            json.append("}");
            respond(exchange, 200, json.toString());
        } catch (RuntimeException e) {
            // Mirror handleApi: never let an unexpected error leak a bare, unlogged 500.
            LOG.warning(() -> "unexpected error handling /stats: " + e.getClass().getSimpleName());
            respond(exchange, 500, "{\"error\":\"internal error\"}");
        }
    }

    private void handleApi(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            // GET reads and POST writes are both forwarded; any other verb is rejected outright.
            boolean isGet = "GET".equals(method);
            boolean isWrite = "POST".equals(method);
            if (!isGet && !isWrite) {
                respond(exchange, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            if (!hostAllowed(exchange)) {
                respond(exchange, 403, "{\"error\":\"forbidden\"}");
                return;
            }
            if (!authorized(exchange)) {
                respond(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            if (isWrite && !allowWrites) {
                // Writes disabled: refuse the parse/refresh POST rather than spend the API key on a
                // write this sidecar was configured not to make. Reads are unaffected.
                respond(exchange, 403, "{\"error\":\"writes disabled\"}");
                return;
            }
            String openDotaPath = toOpenDotaPath(exchange);
            if (openDotaPath == null) {
                respond(exchange, 404, "{\"error\":\"not found\"}");
                return;
            }
            try {
                // Writes go straight to the client's POST path (never the L2 GET cache). Reads route
                // through the L2 decorator when enabled, else the bare client exactly as today.
                String body = isWrite ? client.postJson(openDotaPath)
                        : gateway != null ? gateway.get(openDotaPath) : client.getJson(openDotaPath);
                respond(exchange, 200, body == null ? "" : body);
            } catch (OpenDotaException e) {
                // Mirror the upstream status (a transport failure, status 0, surfaces as
                // 502) and the client's already-redacted/bounded body. Never leak the key.
                int status = e.statusCode() >= 100 ? e.statusCode() : 502;
                String body = e.responseBody();
                respond(exchange, status, body == null ? "" : body);
            }
        } catch (RuntimeException e) {
            LOG.warning(() -> "unexpected error handling " + exchange.getRequestURI().getRawPath()
                    + ": " + e.getClass().getSimpleName());
            respond(exchange, 500, "{\"error\":\"internal error\"}");
        }
    }

    /**
     * Whether the request may proceed: always true when no token is configured; otherwise the
     * {@value #TOKEN_HEADER} header must match the configured secret (constant-time comparison).
     */
    private boolean authorized(HttpExchange exchange) {
        if (token == null) {
            return true;
        }
        String presented = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Anti-DNS-rebinding guard for the token-less loopback default. When no token is configured the only
     * legitimate callers are local agents reaching {@code 127.0.0.1}/{@code localhost}, so a request whose
     * {@code Host} header is anything else is refused: a malicious web page that rebinds its own hostname
     * to this loopback port still sends that hostname as {@code Host}, and page JavaScript cannot forge it
     * (it is a forbidden header). The value is matched as a literal — never DNS-resolved — because
     * resolving an attacker hostname the victim already rebound to {@code 127.0.0.1} would report loopback
     * and defeat the check. When a token IS set the shared-secret requirement already blocks a rebinding
     * page (it cannot know the secret) and the bind may legitimately be a container service name, so the
     * host check is skipped there.
     */
    private boolean hostAllowed(HttpExchange exchange) {
        if (token != null) {
            return true;
        }
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || host.isBlank()) {
            return false;   // HTTP/1.1 mandates Host; a local agent always sends one.
        }
        return LOOPBACK_HOSTS.contains(hostOnly(host));
    }

    /** The host part of a {@code Host} header, lower-cased, with any {@code :port} and IPv6 brackets removed. */
    private static String hostOnly(String host) {
        String h = host.trim();
        if (h.startsWith("[")) {   // IPv6 literal, e.g. [::1]:31337
            int close = h.indexOf(']');
            return (close > 0 ? h.substring(1, close) : h).toLowerCase(Locale.ROOT);
        }
        int colon = h.indexOf(':');
        return (colon >= 0 ? h.substring(0, colon) : h).toLowerCase(Locale.ROOT);
    }

    /** Translate an inbound {@code /api/...} request target into an OpenDota path with its query. */
    private static String toOpenDotaPath(HttpExchange exchange) {
        String rawPath = exchange.getRequestURI().getRawPath();   // e.g. /api/players/123
        if (!rawPath.startsWith(API_PREFIX)) {
            return null;
        }
        String path = rawPath.substring(API_PREFIX.length());     // e.g. /players/123
        if (path.isEmpty() || path.charAt(0) != '/') {
            return null;
        }
        // Reject path traversal. The JDK HttpClient sends the path verbatim (it does not normalize), so a
        // ".." would let a caller escape the intended /api/<endpoint> shape and reach other paths under
        // api.opendota.com on the sidecar's key. OpenDota endpoint paths are fixed names and numeric ids —
        // every dynamic value rides in the query string (handled separately below) — so a '%' in the PATH
        // is never legitimate. Reject it outright: that closes percent-encoded dot-segments (%2e%2e) and
        // encoded separators (%2f/%5c) that would otherwise pass the literal check and be forwarded
        // verbatim, where a normalizing upstream/CDN could collapse them back into a traversal.
        if (path.indexOf('%') >= 0) {
            return null;
        }
        for (String segment : path.split("[/\\\\]")) {
            if (segment.equals("..") || segment.equals(".")) {
                return null;
            }
        }
        String query = exchange.getRequestURI().getRawQuery();
        return query == null ? path : path + "?" + query;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            if (bytes.length > 0) {
                os.write(bytes);
            }
        }
    }

    /**
     * Stop the HTTP server and release downstream resources. When the L2 gateway is present it owns
     * closing both the SQLite store and the client; otherwise the client is closed directly.
     */
    @Override
    public void close() {
        server.stop(0);
        if (gateway != null) {
            gateway.close();
        } else {
            client.close();
        }
    }
}

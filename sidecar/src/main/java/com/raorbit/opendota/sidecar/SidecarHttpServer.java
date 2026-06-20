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
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * A loopback-only HTTP front end for a single shared {@link OpenDotaClient}.
 *
 * <p>Several MCP server processes on one machine each forward their OpenDota GETs
 * here, so the one rate limiter, cache and single-flight in the wrapped client are
 * shared across all of them and OpenDota's real per-key budget is honoured exactly
 * once. The server binds {@code 127.0.0.1} only, so it carries no authentication or
 * TLS surface (and the API key it holds never leaves this machine).
 *
 * <p>Request contract: {@code GET /api/<openDotaPath>[?query]} maps 1:1 to the
 * OpenDota path {@code /<openDotaPath>[?query]} — mirroring the real base shape, so
 * a forwarding client only has to retarget its base URL. A 2xx returns the raw JSON
 * body verbatim; an upstream error is mirrored as that status with the client's
 * already-redacted, length-bounded error body (a transport failure, status {@code 0},
 * becomes {@code 502}). {@code GET /health} returns {@code 200}.
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
        this(port, client, null, token);
    }

    /**
     * @param port    loopback port to bind, or {@code 0} to pick an ephemeral one (tests)
     * @param client  the shared upstream client (used for {@code /stats}); when {@code gateway} is
     *                non-null the gateway owns closing it, else this server does
     * @param gateway optional durable L2 decorator; when non-null, {@code /api} fetches route through
     *                it instead of the bare client, and it owns closing the client + SQLite store
     * @param token   optional shared secret gating {@code /api} and {@code /stats}
     */
    public SidecarHttpServer(int port, OpenDotaClient client, L2CachingGateway gateway, String token)
            throws IOException {
        this.client = client;
        this.gateway = gateway;
        String trimmed = token == null ? null : token.trim();
        this.token = (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext("/health", this::handleHealth);
        this.server.createContext("/stats", this::handleStats);
        this.server.createContext(API_PREFIX, this::handleApi);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    public void start() {
        server.start();
        LOG.info(() -> "opendota-sidecar listening on http://127.0.0.1:" + port()
                + " (keyed=" + client.isKeyed() + ", auth=" + (token != null) + ", l2=" + (gateway != null) + ")");
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
                        .append(",\"l2StoreSkippedUnparsed\":").append(l2.l2StoreSkippedUnparsed())
                        .append(",\"l2PatchBust\":").append(l2.l2PatchBust())
                        .append(",\"l2Error\":").append(l2.l2Error())
                        .append(",\"noStore\":").append(l2.noStore());
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
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"method not allowed\"}");
                return;
            }
            if (!authorized(exchange)) {
                respond(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            String openDotaPath = toOpenDotaPath(exchange);
            if (openDotaPath == null) {
                respond(exchange, 404, "{\"error\":\"not found\"}");
                return;
            }
            try {
                // Route through the L2 decorator when enabled, else the bare client exactly as today.
                String body = gateway != null ? gateway.get(openDotaPath) : client.getJson(openDotaPath);
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

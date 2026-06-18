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

    private final HttpServer server;
    private final OpenDotaClient client;
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
        this.client = client;
        String trimmed = token == null ? null : token.trim();
        this.token = (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext("/health", this::handleHealth);
        this.server.createContext(API_PREFIX, this::handleApi);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    public void start() {
        server.start();
        LOG.info(() -> "opendota-sidecar listening on http://127.0.0.1:" + port()
                + " (keyed=" + client.isKeyed() + ", auth=" + (token != null) + ")");
    }

    /** The bound port (useful when constructed with port {@code 0}). */
    public int port() {
        return server.getAddress().getPort();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        respond(exchange, 200, "{\"status\":\"ok\"}");
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
                String body = client.getJson(openDotaPath);
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

    /** Stop the HTTP server and release the shared client's transport resources. */
    @Override
    public void close() {
        server.stop(0);
        client.close();
    }
}

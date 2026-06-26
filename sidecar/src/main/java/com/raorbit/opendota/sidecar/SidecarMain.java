package com.raorbit.opendota.sidecar;

import com.raorbit.opendota.client.OpenDotaClient;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

/**
 * Entry point for the shared OpenDota sidecar.
 *
 * <p>Reads the optional {@code OPENDOTA_API_KEY} (held here so the agent processes
 * never need it) and a port — system property {@code opendota.sidecar.port} (or the
 * dashed {@code opendota.sidecar-port} the agent's Spring property uses), else env
 * {@code OPENDOTA_SIDECAR_PORT}, else {@value #DEFAULT_PORT} — then starts the
 * loopback HTTP server and blocks until the JVM is shut down.
 *
 * <p>Forwarding agents can both read ({@code GET}) and queue writes ({@code POST} — parse requests /
 * player refreshes) through the sidecar; it holds the key and owns the one rate limiter and cache.
 *
 * <p>Run it once per machine before launching the agents:
 * <pre>{@code OPENDOTA_API_KEY=<uuid> java -jar opendota-sidecar-1.2.0.jar}</pre>
 *
 * <p>This process is a plain HTTP server, not the stdio MCP transport, so logging to
 * the console is fine; redirect it to a file if you run it in the background.
 */
public final class SidecarMain {

    private static final Logger LOG = Logger.getLogger(SidecarMain.class.getName());
    private static final int DEFAULT_PORT = 31337;
    private static final String DEFAULT_BIND_HOST = "127.0.0.1";

    private SidecarMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = resolvePort();
        String bindHost = resolveBindHost();
        String token = resolveToken();
        // Fail closed: a non-loopback bind with no token would expose /api and /stats — and the
        // API key the sidecar holds — unauthenticated to anything that can reach the bind address.
        // Refuse to start rather than silently running open (the token gate is the only protection).
        if (requiresToken(bindHost) && isBlank(token)) {
            LOG.severe("refusing to start: bind host '" + bindHost + "' is not loopback but no token "
                    + "is set, which would expose /api and /stats (and the API key) unauthenticated. "
                    + "Set OPENDOTA_SIDECAR_TOKEN (or -Dopendota.sidecar.token), or bind 127.0.0.1.");
            System.exit(1);
            return;   // unreachable after exit; keeps the compiler happy
        }
        OpenDotaClient client = new OpenDotaClient(System.getenv("OPENDOTA_API_KEY"));
        if (!client.isKeyed()) {
            // Easy to miss: the agents no longer carry the key, so this process is the only
            // place it must be set. Keyless silently caps every agent at the 60/min tier.
            LOG.warning("OPENDOTA_API_KEY is not set: running keyless, so every forwarding agent "
                    + "jointly shares OpenDota's 60 requests/minute keyless limit. Set the key on "
                    + "this process to share the 300/minute keyed budget instead.");
        }
        // Optional durable L2 tier (off by default). When enabled, wrap the client in the gateway;
        // if the SQLite store cannot open, log and fall back to the bare client rather than failing
        // start-up — L2 is a best-effort accelerator, never a hard dependency.
        L2CachingGateway gateway = maybeBuildGateway(client);

        SidecarHttpServer server;
        try {
            server = new SidecarHttpServer(bindHost, port, client, gateway, token);
        } catch (BindException e) {
            // Most likely a sidecar is already running on this port (the design is one shared
            // process per machine). Fail fast with an actionable message, not a raw stack trace.
            closeQuietly(gateway, client);
            LOG.severe("cannot bind " + bindHost + ":" + port + " (" + e.getMessage() + ") — is a sidecar "
                    + "already running, or the port already in use? Stop the other process or pick "
                    + "another port via OPENDOTA_SIDECAR_PORT / -Dopendota.sidecar.port.");
            System.exit(1);
            return;   // unreachable after exit; keeps 'server' definitely assigned for the compiler
        } catch (IOException e) {
            // Any other failure to create the server: still close the gateway (and its SQLite
            // store) / client rather than escaping main() and leaking them on the way out.
            closeQuietly(gateway, client);
            LOG.severe("cannot start HTTP server on " + bindHost + ":" + port + " ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + ").");
            System.exit(1);
            return;   // unreachable after exit; keeps 'server' definitely assigned for the compiler
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "sidecar-shutdown"));
        server.start();
        // Start the proactive watched-match parse poll (no-op unless watched players are configured with
        // auto-parse on). The server is up first; the shutdown hook closes the gateway, stopping the poll.
        if (gateway != null) {
            gateway.startWatchedParsePoll();
        }
        // Park the main thread so the process stays up as a long-lived service until
        // the JVM is signalled to stop (the shutdown hook then closes the server).
        new CountDownLatch(1).await();
    }

    /** Close the gateway (which closes the wrapped client) if present, else just the client. */
    private static void closeQuietly(L2CachingGateway gateway, OpenDotaClient client) {
        if (gateway != null) {
            gateway.close();
        } else {
            client.close();
        }
    }

    /**
     * Build the durable L2 gateway when the feature flag is on, else {@code null} (so the sidecar
     * opens no SQLite file and behaves exactly as today). A failure to open the store is logged and
     * degrades to the bare client — the cache is an accelerator, not a dependency.
     */
    static L2CachingGateway maybeBuildGateway(OpenDotaClient client) {
        if (!L2Config.isEnabled()) {
            return null;
        }
        L2Config config = L2Config.fromEnvironment();
        try {
            L2Store store = new L2Store(config.dbPath(), L2Store.SCHEMA_VERSION, config.readPoolSize());
            LOG.info(() -> "L2 durable cache enabled at " + config.dbPath());
            return new L2CachingGateway(client, store, config);
        } catch (SQLException e) {
            LOG.warning(() -> "L2 durable cache could not open " + config.dbPath() + " ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + "); running without L2.");
            return null;
        }
    }

    /**
     * Resolve the port to bind. Precedence: system property {@code opendota.sidecar.port},
     * then {@code opendota.sidecar-port} (the dashed spelling the agent's Spring property
     * uses — accepted here too so the two sides can't be silently mismatched by separator),
     * then env {@code OPENDOTA_SIDECAR_PORT}, else {@value #DEFAULT_PORT}. A non-numeric or
     * out-of-range value logs a warning and falls back to the default rather than crashing.
     */
    static int resolvePort() {
        String raw = System.getProperty("opendota.sidecar.port",
                System.getProperty("opendota.sidecar-port", System.getenv("OPENDOTA_SIDECAR_PORT")));
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PORT;
        }
        String value = raw.trim();
        int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            LOG.warning(() -> "invalid sidecar port '" + value + "', using default " + DEFAULT_PORT);
            return DEFAULT_PORT;
        }
        if (port < 1 || port > 65535) {
            LOG.warning(() -> "sidecar port " + value + " out of range 1-65535, using default " + DEFAULT_PORT);
            return DEFAULT_PORT;
        }
        return port;
    }

    /**
     * Resolve the optional shared-secret token that gates {@code /api/*}: system property
     * {@code opendota.sidecar.token} (or the dashed {@code opendota.sidecar-token}), else env
     * {@code OPENDOTA_SIDECAR_TOKEN}, else {@code null} (auth disabled — any local caller accepted).
     */
    static String resolveToken() {
        return System.getProperty("opendota.sidecar.token",
                System.getProperty("opendota.sidecar-token", System.getenv("OPENDOTA_SIDECAR_TOKEN")));
    }

    /**
     * Resolve the host/interface to bind. Precedence: system property {@code opendota.sidecar.bind},
     * then the dashed {@code opendota.sidecar-bind}, then env {@code OPENDOTA_SIDECAR_BIND}, else
     * {@value #DEFAULT_BIND_HOST}. A blank value falls back to the default rather than binding a
     * wildcard. Set {@code 0.0.0.0} only when the sidecar must be reachable across a container or
     * network boundary, and gate it with a token (OPENDOTA_SIDECAR_TOKEN) in that case.
     */
    static String resolveBindHost() {
        String raw = System.getProperty("opendota.sidecar.bind",
                System.getProperty("opendota.sidecar-bind", System.getenv("OPENDOTA_SIDECAR_BIND")));
        if (raw == null || raw.isBlank()) {
            return DEFAULT_BIND_HOST;
        }
        return raw.trim();
    }

    /**
     * Whether binding {@code bindHost} reaches beyond loopback, in which case a shared-secret token is
     * mandatory (the sidecar holds the API key and has no other auth). An unresolvable host is treated
     * as exposed, so the guard fails safe rather than silently allowing an open bind.
     */
    static boolean requiresToken(String bindHost) {
        try {
            return !InetAddress.getByName(bindHost).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return true;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

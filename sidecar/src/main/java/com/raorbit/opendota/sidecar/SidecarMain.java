package com.raorbit.opendota.sidecar;

import com.raorbit.opendota.client.OpenDotaClient;

import java.io.IOException;
import java.net.BindException;
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
 * <p>Run it once per machine before launching the agents:
 * <pre>{@code OPENDOTA_API_KEY=<uuid> java -jar opendota-sidecar-1.0.0.jar}</pre>
 *
 * <p>This process is a plain HTTP server, not the stdio MCP transport, so logging to
 * the console is fine; redirect it to a file if you run it in the background.
 */
public final class SidecarMain {

    private static final Logger LOG = Logger.getLogger(SidecarMain.class.getName());
    private static final int DEFAULT_PORT = 31337;

    private SidecarMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = resolvePort();
        OpenDotaClient client = new OpenDotaClient(System.getenv("OPENDOTA_API_KEY"));
        if (!client.isKeyed()) {
            // Easy to miss: the agents no longer carry the key, so this process is the only
            // place it must be set. Keyless silently caps every agent at the 60/min tier.
            LOG.warning("OPENDOTA_API_KEY is not set: running keyless, so every forwarding agent "
                    + "jointly shares OpenDota's 60 requests/minute keyless limit. Set the key on "
                    + "this process to share the 300/minute keyed budget instead.");
        }
        SidecarHttpServer server;
        try {
            server = new SidecarHttpServer(port, client, resolveToken());
        } catch (BindException e) {
            // Most likely a sidecar is already running on this port (the design is one shared
            // process per machine). Fail fast with an actionable message, not a raw stack trace.
            client.close();
            LOG.severe("cannot bind 127.0.0.1:" + port + " (" + e.getMessage() + ") — is a sidecar "
                    + "already running, or the port already in use? Stop the other process or pick "
                    + "another port via OPENDOTA_SIDECAR_PORT / -Dopendota.sidecar.port.");
            System.exit(1);
            return;   // unreachable after exit; keeps 'server' definitely assigned for the compiler
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "sidecar-shutdown"));
        server.start();
        // Park the main thread so the process stays up as a long-lived service until
        // the JVM is signalled to stop (the shutdown hook then closes the server).
        new CountDownLatch(1).await();
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
}

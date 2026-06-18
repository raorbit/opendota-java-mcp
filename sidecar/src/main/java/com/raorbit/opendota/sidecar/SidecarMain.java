package com.raorbit.opendota.sidecar;

import com.raorbit.opendota.client.OpenDotaClient;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

/**
 * Entry point for the shared OpenDota sidecar.
 *
 * <p>Reads the optional {@code OPENDOTA_API_KEY} (held here so the agent processes
 * never need it) and a port — system property {@code opendota.sidecar.port}, else
 * env {@code OPENDOTA_SIDECAR_PORT}, else {@value #DEFAULT_PORT} — then starts the
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
        SidecarHttpServer server = new SidecarHttpServer(port, client);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "sidecar-shutdown"));
        server.start();
        // Park the main thread so the process stays up as a long-lived service until
        // the JVM is signalled to stop (the shutdown hook then closes the server).
        new CountDownLatch(1).await();
    }

    private static int resolvePort() {
        String raw = System.getProperty("opendota.sidecar.port", System.getenv("OPENDOTA_SIDECAR_PORT"));
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            LOG.warning(() -> "invalid sidecar port '" + raw + "', using default " + DEFAULT_PORT);
            return DEFAULT_PORT;
        }
    }
}

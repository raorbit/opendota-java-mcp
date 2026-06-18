package com.raorbit.opendota.client;

import com.raorbit.opendota.sidecar.SidecarHttpServer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end test of the full forwarding loop the multi-agent feature actually runs:
 * a {@link OpenDotaClient#forwardingTo forwarding client} (the agent side) → a real
 * {@link SidecarHttpServer} over loopback HTTP → the sidecar's shared direct
 * {@link OpenDotaClient} (L1 cache + single-flight + rate limiter) → a fake OpenDota
 * upstream. The existing {@code SidecarHttpServerTest} drives the sidecar with a plain
 * HttpClient; this test drives it through the real forwarding client so the forwarding
 * mode, the loopback hop, and the sidecar's single-flight are all exercised together.
 *
 * <p>Lives in the {@code client} package to reach the package-private base-URL constructor.
 */
class ForwardingEndToEndTest {

    private HttpServer upstream;
    private SidecarHttpServer sidecar;
    private OpenDotaClient forwardingClient;
    private String upstreamBase;
    private final AtomicInteger upstreamHits = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(Executors.newCachedThreadPool());
        upstream.start();
        upstreamBase = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/api";

        // The sidecar's single shared (direct) client, pointed at the fake upstream.
        sidecar = new SidecarHttpServer(0, new OpenDotaClient(null, upstreamBase));
        sidecar.start();
        // The agent-side forwarding client, pointed at the sidecar.
        forwardingClient = OpenDotaClient.forwardingTo("http://127.0.0.1:" + sidecar.port() + "/api", 16L * 1024 * 1024);
    }

    @AfterEach
    void tearDown() {
        if (forwardingClient != null) {
            forwardingClient.close();
        }
        if (sidecar != null) {
            sidecar.close();
        }
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    private void stubUpstream(String apiPath, int status, String body) {
        upstream.createContext(apiPath, exchange -> {
            upstreamHits.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                if (bytes.length > 0) {
                    os.write(bytes);
                }
            }
        });
    }

    private static int freeLoopbackPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    @Test
    void forwardingClientGetReturnsUpstreamBodyVerbatim() throws Exception {
        String body = "[{\"id\":1,\"name\":\"npc_dota_hero_antimage\"}]";
        stubUpstream("/api/heroes", 200, body);

        assertThat(forwardingClient.getJson("/heroes")).isEqualTo(body);
        assertThat(upstreamHits.get()).isEqualTo(1);
    }

    @Test
    void upstreamErrorStatusIsMirroredThroughTheForwardingClient() throws Exception {
        stubUpstream("/api/players/999", 404, "{\"error\":\"Not Found\"}");

        assertThatThrownBy(() -> forwardingClient.getJson("/players/999"))
                .isInstanceOf(OpenDotaException.class)
                .satisfies(t -> assertThat(((OpenDotaException) t).statusCode()).isEqualTo(404));
    }

    @Test
    void sidecarUpstreamTransportFailureSurfacesAs502ToTheForwardingClient() throws Exception {
        // A sidecar whose own upstream is dead: its direct client yields status 0, the sidecar
        // maps that to 502, and the forwarding client surfaces a 502 OpenDotaException.
        int deadPort = freeLoopbackPort();
        try (SidecarHttpServer deadSidecar =
                     new SidecarHttpServer(0, new OpenDotaClient(null, "http://127.0.0.1:" + deadPort + "/api"))) {
            deadSidecar.start();
            try (OpenDotaClient fwd =
                         OpenDotaClient.forwardingTo("http://127.0.0.1:" + deadSidecar.port() + "/api", 16L * 1024 * 1024)) {
                assertThatThrownBy(() -> fwd.getJson("/heroes"))
                        .isInstanceOf(OpenDotaException.class)
                        .satisfies(t -> assertThat(((OpenDotaException) t).statusCode()).isEqualTo(502));
            }
        }
    }

    @Test
    void sidecarDownFailsFastAsTransportError() throws Exception {
        int closedPort = freeLoopbackPort();
        try (OpenDotaClient fwd =
                     OpenDotaClient.forwardingTo("http://127.0.0.1:" + closedPort + "/api", 16L * 1024 * 1024)) {
            long startNanos = System.nanoTime();
            assertThatThrownBy(() -> fwd.getJson("/heroes"))
                    .isInstanceOf(OpenDotaException.class)
                    .satisfies(t -> assertThat(((OpenDotaException) t).statusCode()).isEqualTo(0));
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            assertThat(elapsedMs).isLessThan(20_000L);
        }
    }

    @Test
    void concurrentForwardingMissesCollapseToOneUpstreamCall() throws Exception {
        // The forwarding client bypasses its OWN single-flight, so N concurrent identical GETs each
        // make a loopback call to the sidecar; the sidecar's shared direct client must collapse them
        // into a single upstream call. /players/* is cacheable, so followers await the one leader.
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
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return forwardingClient.getJson("/players/123");
            }));
        }
        start.countDown();
        Thread.sleep(250);   // let all callers reach the sidecar's in-flight wait
        release.countDown();

        for (Future<String> f : futures) {
            assertThat(f.get(5, TimeUnit.SECONDS)).isEqualTo(body);
        }
        pool.shutdownNow();

        assertThat(upstreamHits.get()).isEqualTo(1);
    }
}

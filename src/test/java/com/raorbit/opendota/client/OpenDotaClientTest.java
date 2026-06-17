package com.raorbit.opendota.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link OpenDotaClient} against a local in-process HTTP
 * server bound to a dynamic port.
 *
 * <p><strong>Why not WireMock?</strong> The project pins {@code org.wiremock:wiremock:3.13.2},
 * whose only bundled server implementation is {@code Jetty11HttpServer}, built
 * against the Jetty 11 Handler API ({@code org.eclipse.jetty.server.handler.AbstractHandler},
 * {@code HandlerCollection}). Spring Boot's dependency management on this
 * classpath forces {@code jetty-server} / {@code jetty-http} / {@code jetty-io}
 * / {@code jetty-util} to <em>12.0.36</em>, where those classes were removed in
 * the Jetty 12 Handler rewrite. WireMock's {@code HttpServerFactoryLoader} reads
 * {@code org.eclipse.jetty.util.Jetty.VERSION} (now "12.0.36"), finds no
 * Jetty-11 factory, and fails startup with
 * {@code "Jetty 11 is not present ..."}; forcing the Jetty-11 factory instead
 * throws {@code NoClassDefFoundError} at runtime. Resolving this would require a
 * pom/dependency change, which is out of scope for this work package. The JDK's
 * {@link com.sun.net.httpserver.HttpServer} is a zero-dependency, deterministic
 * substitute that serves real HTTP over a real socket, exercising the client's
 * real {@link java.net.http.HttpClient} exactly as a remote API would.
 *
 * <p>The base URL is built with a {@code /api} path segment, mirroring the real
 * OpenDota base shape, so {@code getJson("/heroes")} hits {@code /api/heroes}.
 */
class OpenDotaClientTest {

    private HttpServer server;
    private String base;
    /** Records every request path+query the server received, in order. */
    private final List<String> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        base = "http://localhost:" + server.getAddress().getPort() + "/api";
        received.clear();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Register a handler for an exact request path that returns a fixed status/body. */
    private void stub(String path, int status, String body) {
        server.createContext(path, exchange -> respond(exchange, status, body));
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        // Capture the full request target (path plus raw query) for verification.
        String target = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();
        received.add(query == null ? target : target + "?" + query);

        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            if (bytes.length > 0) {
                os.write(bytes);
            }
        }
    }

    @Test
    void getJsonReturnsBodyVerbatimOn200() throws Exception {
        String body = "[{\"id\":1,\"name\":\"npc_dota_hero_antimage\"}]";
        stub("/api/heroes", 200, body);

        OpenDotaClient client = new OpenDotaClient(null, base);

        assertThat(client.getJson("/heroes")).isEqualTo(body);
    }

    @Test
    void getJsonThrowsOpenDotaExceptionOn404() {
        String errorBody = "{\"error\":\"Not Found\"}";
        stub("/api/players/999", 404, errorBody);

        OpenDotaClient client = new OpenDotaClient(null, base);

        assertThatThrownBy(() -> client.getJson("/players/999"))
                .isInstanceOf(OpenDotaException.class)
                .satisfies(t -> {
                    OpenDotaException e = (OpenDotaException) t;
                    assertThat(e.statusCode()).isEqualTo(404);
                    assertThat(e.endpoint()).isEqualTo("/players/999");
                    assertThat(e.responseBody()).isEqualTo(errorBody);
                });
    }

    @Test
    void transportFailureYieldsStatusCodeZero() {
        // Stop the server so the port is dead; the connection attempt fails at
        // the transport level, which the client maps to statusCode() == 0.
        server.stop(0);
        server = null;

        OpenDotaClient client = new OpenDotaClient(null, base);

        assertThatThrownBy(() -> client.getJson("/heroes"))
                .isInstanceOf(OpenDotaException.class)
                .satisfies(t -> assertThat(((OpenDotaException) t).statusCode()).isEqualTo(0));
    }

    @Test
    void cacheableResponseIsServedFromCacheOnSecondCall() throws Exception {
        // /players/* has a 30s TTL, so two identical calls should hit upstream once.
        String body = "{\"profile\":{\"account_id\":123}}";
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/api/players/123", exchange -> {
            hits.incrementAndGet();
            respond(exchange, 200, body);
        });

        OpenDotaClient client = new OpenDotaClient(null, base);

        assertThat(client.getJson("/players/123")).isEqualTo(body);
        assertThat(client.getJson("/players/123")).isEqualTo(body);

        // Exactly ONE upstream request proves the second call was served from cache.
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void keyedClientAppendsApiKeyQueryParam() throws Exception {
        stub("/api/heroes", 200, "[]");

        String apiKey = "00000000-0000-0000-0000-000000000000";
        OpenDotaClient client = new OpenDotaClient(apiKey, base);

        assertThat(client.isKeyed()).isTrue();
        client.getJson("/heroes");

        assertThat(received).hasSize(1);
        assertThat(received.get(0)).contains("api_key=" + apiKey);
    }

    @Test
    void keylessClientDoesNotAppendApiKeyQueryParam() throws Exception {
        stub("/api/heroes", 200, "[]");

        OpenDotaClient client = new OpenDotaClient(null, base);

        assertThat(client.isKeyed()).isFalse();
        client.getJson("/heroes");

        assertThat(received).hasSize(1);
        assertThat(received.get(0)).doesNotContain("api_key");
    }
}

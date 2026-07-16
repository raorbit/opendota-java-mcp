package com.raorbit.opendota;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end MCP <em>protocol</em> smoke test — the coverage {@link McpWiringTest} deliberately
 * leaves out (it disables the MCP server so the context loads without grabbing {@code System.in}).
 * This test boots the real application as a child JVM on the actual stdio transport and drives
 * {@code initialize → notifications/initialized → tools/list → tools/call} over real pipes, exactly
 * as an MCP client would. That exercises, at the wire level: the MCP SDK 0.18.3 stdio framing, the
 * tool-schema generation for every registered {@code @Tool}, the json-schema-validator 2.0.0 pin
 * (schema validation runs on the tools/call arguments), and stdout cleanliness (any stray
 * {@code System.out} write would corrupt the JSON-RPC framing and fail the response parsing here).
 *
 * <p>The child is configured to forward through a stubbed "sidecar" on an ephemeral loopback port
 * ({@code opendota.sidecar-enabled=true}), so the {@code tools/call} exercises the full
 * client-forwarding path without ever touching the real OpenDota API.
 */
class McpStdioProtocolSmokeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HEALTH_BODY = "{\"status\":\"healthy\",\"source\":\"stub-sidecar\"}";
    /** Generous per-response wait: a cold Spring Boot start on a loaded CI box can take a while. */
    private static final long CHILD_START_TIMEOUT_SECONDS = 90;

    @Test
    @Timeout(180)
    void speaksInitializeToolsListAndToolsCallOverRealStdio(@TempDir Path tmp) throws Exception {
        // A stub sidecar: the child's forwarding client GETs /api/health here for get_health.
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/health", exchange -> {
            byte[] bytes = HEALTH_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        stub.start();

        Process child = null;
        try {
            child = launchServer(tmp, stub.getAddress().getPort());
            StringBuilder stderr = drainStderr(child);

            try (BufferedWriter toChild = new BufferedWriter(new OutputStreamWriter(
                         child.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader fromChild = new BufferedReader(new InputStreamReader(
                         child.getInputStream(), StandardCharsets.UTF_8))) {

                // --- initialize ---
                send(toChild, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                        + "\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"smoke-test\",\"version\":\"0\"}}}");
                JsonNode init = readResponse(fromChild, 1, stderr, child);
                assertThat(init.path("result").path("serverInfo").path("name").asText())
                        .isEqualTo("opendota-mcp");
                // The advertised version is resource-filtered from the pom: it must be a concrete
                // version, never the raw @project.version@ placeholder (guards the filtering).
                String version = init.path("result").path("serverInfo").path("version").asText();
                assertThat(version).isNotBlank().doesNotContain("@project.version@");

                // The client must confirm initialization before issuing requests.
                send(toChild, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

                // --- tools/list: the full registered read-tool surface, with generated schemas ---
                send(toChild, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
                JsonNode toolsList = readResponse(fromChild, 2, stderr, child);
                JsonNode tools = toolsList.path("result").path("tools");
                assertThat(tools.isArray()).as("tools/list result.tools is an array").isTrue();
                List<String> names = new ArrayList<>();
                for (JsonNode tool : tools) {
                    names.add(tool.path("name").asText());
                    // Every tool ships a generated input schema — the surface the schema-validator
                    // pin exists for.
                    assertThat(tool.path("inputSchema").path("type").asText())
                            .as("tool %s has a generated object schema", tool.path("name").asText())
                            .isEqualTo("object");
                }
                assertThat(names).hasSize(47)
                        .contains("get_health", "search_players", "run_sql_explorer")
                        .doesNotContain("request_parse", "refresh_player", "get_parse_request");

                // --- tools/call: a real invocation through the forwarding client to the stub ---
                send(toChild, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{"
                        + "\"name\":\"get_health\",\"arguments\":{}}}");
                JsonNode call = readResponse(fromChild, 3, stderr, child);
                assertThat(call.path("result").path("isError").asBoolean(false))
                        .as("tools/call succeeded: %s", call)
                        .isFalse();
                JsonNode content = call.path("result").path("content");
                assertThat(content.isArray() && content.size() > 0)
                        .as("tools/call returned content: %s", call)
                        .isTrue();
                assertThat(content.get(0).path("text").asText())
                        .as("the stub body made the round trip")
                        .contains("stub-sidecar");
            }
        } finally {
            if (child != null) {
                child.destroy();
                if (!child.waitFor(10, TimeUnit.SECONDS)) {
                    child.destroyForcibly();
                }
            }
            stub.stop(0);
        }
    }

    /**
     * Launch the application as a child JVM on the surefire test classpath (which contains
     * target/classes plus every dependency — no packaged jar needed), configured to forward through
     * the stub sidecar and to log to the temp dir so no per-PID file lands in the real log dir.
     * The classpath rides in a {@code @argfile} so Windows command-length limits can't truncate it.
     */
    private static Process launchServer(Path tmp, int stubPort) throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Path argFile = tmp.resolve("server.args");
        String classpath = System.getProperty("java.class.path");
        List<String> args = List.of(
                "-cp", quoteForArgFile(classpath),
                "-Xshare:off",
                "-Dopendota.sidecar-enabled=true",
                "-Dopendota.sidecar-host=127.0.0.1",
                "-Dopendota.sidecar-port=" + stubPort,
                "-Dlogging.file.name=" + quoteForArgFile(tmp.resolve("smoke.log").toString()),
                OpenDotaMcpApplication.class.getName());
        Files.write(argFile, args, StandardCharsets.UTF_8);
        return new ProcessBuilder(java, "@" + argFile).start();
    }

    /** Quote a token for a java @argfile, escaping backslashes so Windows paths survive parsing. */
    private static String quoteForArgFile(String value) {
        return "\"" + value.replace("\\", "\\\\") + "\"";
    }

    private static void send(BufferedWriter toChild, String json) throws Exception {
        toChild.write(json);
        toChild.write("\n");
        toChild.flush();
    }

    /**
     * Read the next JSON-RPC <em>response</em> line for {@code expectedId} from the child's stdout.
     * Every line on stdout must parse as JSON — the transport owns the stream, so a stray log line
     * is itself a bug this test exists to catch. Notifications/requests from the server (no matching
     * id) are skipped; EOF means the child died, so the buffered stderr is surfaced for diagnosis.
     */
    private static JsonNode readResponse(BufferedReader fromChild, int expectedId,
                                         StringBuilder stderr, Process child) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CHILD_START_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            String line = fromChild.readLine();
            if (line == null) {
                throw new AssertionError("server exited (code "
                        + (child.isAlive() ? "still-alive" : String.valueOf(child.exitValue()))
                        + ") before answering id " + expectedId + "; stderr:\n" + stderr);
            }
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = MAPPER.readTree(line);   // any non-JSON stdout line fails right here
            assertThat(node.path("jsonrpc").asText())
                    .as("every stdout line is JSON-RPC framed: %s", line)
                    .isEqualTo("2.0");
            if (node.has("id") && node.get("id").asInt() == expectedId) {
                assertThat(node.has("error"))
                        .as("id %s answered without an error: %s", expectedId, line)
                        .isFalse();
                return node;
            }
            // A server-initiated notification/request (e.g. a log message) — skip and keep reading.
        }
        throw new AssertionError("timed out waiting for response id " + expectedId
                + "; stderr:\n" + stderr);
    }

    /** Drain the child's stderr on a daemon thread (avoids pipe-buffer deadlock) into a buffer. */
    private static StringBuilder drainStderr(Process child) {
        StringBuilder buffer = new StringBuilder();
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    child.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (buffer) {
                        buffer.append(line).append('\n');
                    }
                }
            } catch (Exception ignored) {
                // the child closing its stderr is expected at shutdown
            }
        }, "smoke-test-stderr-drain");
        t.setDaemon(true);
        t.start();
        return buffer;
    }
}

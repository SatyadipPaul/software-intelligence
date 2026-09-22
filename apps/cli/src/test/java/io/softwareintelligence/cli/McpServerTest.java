package io.softwareintelligence.cli;

import io.softwareintelligence.session.AnalysisSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server, driven one message at a time.
 *
 * <p>A client this project did not write sits on the other end, so these pin the protocol's
 * obligations as much as the answers: what is never replied to, which failures are protocol errors
 * and which are results, and that a changed source tree is never answered from its old graph.
 */
final class McpServerTest {

    private McpServer server;
    private int nextId = 1;

    @AfterEach void close() {
        if (server != null) server.close();
    }

    private static Path repository(Path directory) throws IOException {
        Path file = directory.resolve("src/main/java/demo/PaymentService.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package demo;
                /** Authorizes card payments. */
                public class PaymentService {
                    public void authorize() { }
                }
                """);
        Path caller = directory.resolve("src/main/java/demo/Checkout.java");
        Files.writeString(caller, """
                package demo;
                public class Checkout {
                    private final PaymentService payments = new PaymentService();
                    public void pay() { payments.authorize(); }
                }
                """);
        return directory;
    }

    private McpServer open(Path directory) throws IOException {
        Path repository = repository(directory);
        server = new McpServer(() -> AnalysisSession.open(repository), "test");
        return server;
    }

    private Map<?, ?> request(String method, Map<String, Object> params) {
        String line = JsonCodec.write(Map.of("jsonrpc", "2.0", "id", (long) nextId++, "method", method, "params", params));
        Optional<String> reply = server.handle(line);
        assertTrue(reply.isPresent(), "a request must be answered: " + method);
        return (Map<?, ?>) JsonCodec.parse(reply.get());
    }

    private Map<?, ?> callTool(String name, Map<String, Object> arguments) {
        Map<?, ?> reply = request("tools/call", Map.of("name", name, "arguments", arguments));
        return (Map<?, ?>) reply.get("result");
    }

    private static String text(Map<?, ?> result) {
        return (String) ((Map<?, ?>) ((List<?>) result.get("content")).getFirst()).get("text");
    }

    private static long errorCode(Map<?, ?> reply) {
        return (Long) ((Map<?, ?>) reply.get("error")).get("code");
    }

    @Test void initialize_agrees_the_version_the_client_asked_for_when_it_is_supported(@TempDir Path directory) throws IOException {
        open(directory);
        Map<?, ?> result = (Map<?, ?>) request("initialize", Map.of("protocolVersion", "2025-03-26")).get("result");
        assertEquals("2025-03-26", result.get("protocolVersion"));
        assertTrue(((Map<?, ?>) result.get("capabilities")).containsKey("tools"));
        assertEquals("repo-intel", ((Map<?, ?>) result.get("serverInfo")).get("name"));
    }

    @Test void initialize_offers_its_newest_version_when_the_client_asks_for_an_unknown_one(@TempDir Path directory) throws IOException {
        open(directory);
        Map<?, ?> result = (Map<?, ?>) request("initialize", Map.of("protocolVersion", "1999-01-01")).get("result");
        assertEquals(McpServer.PROTOCOL_VERSIONS.getFirst(), result.get("protocolVersion"));
    }

    @Test void a_notification_is_never_answered(@TempDir Path directory) throws IOException {
        open(directory);
        assertTrue(server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").isEmpty());
        assertTrue(server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{}}").isEmpty(),
                "not even a malformed one: a notification has no id to reply to");
    }

    @Test void protocol_failures_are_json_rpc_errors(@TempDir Path directory) throws IOException {
        open(directory);
        Map<?, ?> parse = (Map<?, ?>) JsonCodec.parse(server.handle("{not json").orElseThrow());
        assertEquals(-32700L, errorCode(parse));
        assertNull(parse.get("id"), "a message that could not be parsed has no id to echo");

        assertEquals(-32600L, errorCode((Map<?, ?>) JsonCodec.parse(server.handle("[1,2]").orElseThrow())));
        assertEquals(-32601L, errorCode(request("resources/list", Map.of())));
        assertEquals(-32602L, errorCode(request("tools/call", Map.of("name", "no_such_tool"))));
        assertEquals(-32602L, errorCode(request("tools/call", Map.of("name", "impact", "arguments", Map.of()))),
                "a missing required argument is a malformed request");
        assertEquals(-32602L, errorCode(request("tools/call",
                Map.of("name", "impact", "arguments", Map.of("symbol", "X", "depth", 99L)))));
    }

    @Test void a_question_with_no_answer_is_a_result_the_assistant_can_read(@TempDir Path directory) throws IOException {
        // Not a protocol error: the request was fine, the symbol just is not there.
        open(directory);
        Map<?, ?> result = callTool("impact", Map.of("symbol", "NothingNamedThis"));
        assertEquals(true, result.get("isError"));
        assertTrue(text(result).contains("no symbol matched"), text(result));
    }

    @Test void every_advertised_tool_is_callable_by_name(@TempDir Path directory) throws IOException {
        open(directory);
        List<?> tools = (List<?>) ((Map<?, ?>) request("tools/list", Map.of()).get("result")).get("tools");
        List<String> names = tools.stream().map(tool -> (String) ((Map<?, ?>) tool).get("name")).toList();
        assertEquals(McpServer.Tools.NAMES, names);
        for (Object tool : tools) {
            Map<?, ?> schema = (Map<?, ?>) ((Map<?, ?>) tool).get("inputSchema");
            assertEquals("object", schema.get("type"));
        }
    }

    @Test void ask_answers_with_cited_evidence(@TempDir Path directory) throws IOException {
        open(directory);
        Map<?, ?> result = callTool("ask", Map.of("question", "how are payments authorized?"));
        assertEquals(false, result.get("isError"), text(result));
        assertTrue(text(result).contains("ANCHORS:"), text(result));
    }

    @Test void impact_reports_the_caller(@TempDir Path directory) throws IOException {
        open(directory);
        Map<?, ?> result = callTool("impact", Map.of("symbol", "PaymentService"));
        assertEquals(false, result.get("isError"), text(result));
        assertTrue(text(result).contains("Checkout"), text(result));
    }

    @Test void an_edit_between_calls_is_analyzed_before_the_next_answer(@TempDir Path directory) throws IOException {
        open(directory);
        assertFalse(text(callTool("impact", Map.of("symbol", "PaymentService"))).contains("source changed"));

        Files.writeString(directory.resolve("src/main/java/demo/Refunds.java"), """
                package demo;
                public class Refunds {
                    private final PaymentService payments = new PaymentService();
                    public void refund() { payments.authorize(); }
                }
                """);

        String after = text(callTool("impact", Map.of("symbol", "PaymentService")));
        assertTrue(after.contains("source changed"), "the rebuild must be announced: " + after);
        assertTrue(after.contains("Refunds"), "and the answer must come from the new graph: " + after);

        assertFalse(text(callTool("impact", Map.of("symbol", "PaymentService"))).contains("source changed"),
                "an unchanged tree must not be rebuilt again");
    }

    @Test void a_descent_is_discarded_when_the_source_changes(@TempDir Path directory) throws IOException {
        // Its cards describe the previous graph; following them would lead into a tree that no
        // longer exists.
        open(directory);
        String started = text(callTool("navigate_start", Map.of("question", "where are payments authorized?")));
        String descent = started.lines().findFirst().orElseThrow().replace("descent_id: ", "");

        Files.writeString(directory.resolve("src/main/java/demo/Extra.java"), "package demo; public class Extra { }");

        Map<?, ?> result = callTool("navigate_choose", Map.of("descent_id", descent, "chosen", List.of("anything")));
        assertEquals(true, result.get("isError"));
        assertTrue(text(result).contains("no descent " + descent + " is open"), text(result));
    }

    @Test void status_answers_while_the_repository_is_still_being_analyzed() throws Exception {
        CountDownLatch analyzing = new CountDownLatch(1);
        server = new McpServer(() -> {
            analyzing.await(30, TimeUnit.SECONDS);
            throw new IOException("never finishes in this test");
        }, "test");
        // Queued on the worker, this would wait behind the analysis it is asking about.
        String status = text(callTool("status", Map.of()));
        assertTrue(status.contains("analyzing"), status);
        analyzing.countDown();
    }

    @Test void a_repository_that_cannot_be_analyzed_fails_each_call_with_the_reason() throws Exception {
        server = new McpServer(() -> { throw new IOException("disk on fire"); }, "test");
        Map<?, ?> result = callTool("impact", Map.of("symbol", "X"));
        assertEquals(true, result.get("isError"));
        assertTrue(text(result).contains("disk on fire"), text(result));
    }
}

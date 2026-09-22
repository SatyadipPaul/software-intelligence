package io.softwareintelligence.cli;

import io.softwareintelligence.indextree.IndexTree;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.ContextPacket;
import io.softwareintelligence.model.GraphJsonWriter;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.GraphQueries;
import io.softwareintelligence.queryengine.NavigationSession;
import io.softwareintelligence.queryengine.RetrievalMode;
import io.softwareintelligence.session.AnalysisSession;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A Model Context Protocol server over stdio, answering from one warm {@link AnalysisSession}.
 *
 * <p>This is what the {@code navigate} design was shaped for. That command pins a tree, prints
 * cards, and accepts only ids that appeared on them, so that an assistant can steer a descent
 * without being able to assert anything about the code. Run as a command, a person carries the cards
 * between a terminal and a chat window and back. Served, the assistant calls the tools itself, and
 * every call after the first costs tens of milliseconds rather than tens of seconds.
 *
 * <h2>Protocol</h2>
 *
 * <p>JSON-RPC 2.0, one message per line. {@code initialize}, {@code ping}, {@code tools/list} and
 * {@code tools/call} are answered; notifications are never answered, as the protocol requires; any
 * other method is {@code -32601}. A tool that fails returns a result marked {@code isError} with the
 * reason as text, so the assistant can read it - a JSON-RPC error is reserved for a request that was
 * malformed, not for a question that had no answer.
 *
 * <h2>Stdout belongs to the protocol</h2>
 *
 * <p>Every byte on stdout must be a protocol message. Anything else - a warning printed by a
 * library, a stray debug line - corrupts the stream, and the client disconnects with an error that
 * names none of this. {@link ServeCommand} therefore points {@code System.out} at stderr before
 * anything runs and hands the real stdout to this class alone.
 *
 * <h2>Threading</h2>
 *
 * <p>The session is confined to one worker thread, because {@code AnalysisSession} is not
 * thread-safe and says so. The worker opens the session as its first task, so {@code initialize} is
 * answered at once rather than after a thirty-second analysis a client may not wait for; the first
 * tool call simply queues behind the open.
 */
final class McpServer implements AutoCloseable {

    /** Newest first. A client asking for another gets the newest, and decides for itself. */
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

    /** Descents kept at once. A long-running server must not grow without bound on abandoned ones. */
    private static final int MAX_DESCENTS = 32;

    private static final String REPLY_BY_TOOL = """
            Reply by calling the `navigate_choose` tool with this descent's `descent_id` and the ids
            you chose as `chosen`. Do not describe the code; only choose where to look.
            """;

    private final String version;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "repo-intel-session");
        thread.setDaemon(true);
        return thread;
    });
    private final Future<AnalysisSession> opening;
    private final Map<String, NavigationSession.State> descents = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, NavigationSession.State> eldest) {
            return size() > MAX_DESCENTS;
        }
    };
    private int nextDescent = 1;

    McpServer(Callable<AnalysisSession> opener, String version) {
        this.version = version;
        this.opening = worker.submit(opener);
    }

    /** Reads messages until the client closes stdin, answering each on {@code out}. */
    void serve(BufferedReader in, PrintStream out) throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            Optional<String> reply = handle(line);
            if (reply.isPresent()) {
                out.print(reply.get());
                out.print('\n');
                out.flush();
            }
        }
    }

    /** One message in, at most one message out. Package-private so tests can drive it directly. */
    Optional<String> handle(String line) {
        Object parsed;
        try {
            parsed = JsonCodec.parse(line);
        } catch (JsonCodec.ParseException malformed) {
            return Optional.of(error(null, -32700, "Parse error: " + malformed.getMessage()));
        }
        if (!(parsed instanceof Map<?, ?> message)) {
            // Batches were removed from the protocol in 2025-06-18; a bare value was never valid.
            return Optional.of(error(null, -32600, "Invalid Request: expected a single JSON-RPC object"));
        }
        Object id = message.get("id");
        boolean notification = !message.containsKey("id");
        if (!"2.0".equals(message.get("jsonrpc")) || !(message.get("method") instanceof String method)) {
            return notification ? Optional.empty() : Optional.of(error(id, -32600, "Invalid Request"));
        }
        Map<?, ?> params = message.get("params") instanceof Map<?, ?> p ? p : Map.of();

        if (notification) return Optional.empty();
        try {
            Object result = switch (method) {
                case "initialize" -> initialize(params);
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", Tools.definitions());
                case "tools/call" -> call(params);
                default -> null;
            };
            if (result == null) return Optional.of(error(id, -32601, "Method not found: " + method));
            return Optional.of(response(id, result));
        } catch (InvalidParams invalid) {
            return Optional.of(error(id, -32602, "Invalid params: " + invalid.getMessage()));
        }
    }

    private Map<String, Object> initialize(Map<?, ?> params) {
        Object requested = params.get("protocolVersion");
        String agreed = PROTOCOL_VERSIONS.contains(requested) ? (String) requested : PROTOCOL_VERSIONS.getFirst();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", agreed);
        result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        result.put("serverInfo", Map.of("name", "repo-intel", "version", version));
        result.put("instructions", """
                Answers questions about one Java repository from a deterministic, evidence-bearing \
                graph. Every claim in an answer cites a file and line, and anything the graph cannot \
                support is withheld rather than guessed. Start with `ask`. For a question where you \
                want to steer where to look, use `navigate_start` and `navigate_choose`. The graph is \
                rebuilt automatically when source files change between calls.""");
        return result;
    }

    // ------------------------------------------------------------------------------------ tools

    private Map<String, Object> call(Map<?, ?> params) {
        if (!(params.get("name") instanceof String name)) throw new InvalidParams("`name` is required");
        Map<?, ?> arguments = params.get("arguments") instanceof Map<?, ?> a ? a : Map.of();
        if (!Tools.NAMES.contains(name)) throw new InvalidParams("unknown tool: " + name);

        // Arguments are checked here, on the protocol thread, so a malformed call is rejected
        // without queueing behind an analysis.
        Callable<String> work = switch (name) {
            case "ask" -> ask(arguments);
            case "impact" -> impact(arguments);
            case "context" -> context(arguments);
            case "navigate_start" -> navigateStart(arguments);
            case "navigate_choose" -> navigateChoose(arguments);
            case "status" -> this::status;
            default -> throw new InvalidParams("unknown tool: " + name);
        };
        if (name.equals("status") && !opening.isDone()) {
            // Answered here while the open is still running: queued on the worker, it would wait
            // behind the very analysis it is being asked about, for as long as that takes.
            return result("state: analyzing (the first tool call will wait for this to finish)\n", false);
        }
        try {
            return result(worker.submit(work).get(), false);
        } catch (ExecutionException failed) {
            return result(explain(failed.getCause()), true);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return result("interrupted", true);
        }
    }

    private Callable<String> ask(Map<?, ?> arguments) {
        String question = requiredString(arguments, "question");
        int anchors = optionalInt(arguments, "anchors", 1, 1, 10);
        RetrievalMode retrieval = optionalEnum(arguments, "retrieval", RetrievalMode.HYBRID, Tools.RETRIEVAL);
        boolean explain = optionalBoolean(arguments, "explain", false);
        return () -> withCurrentSession((session, out) -> {
            AskCommand.Settings settings = new AskCommand.Settings(AskCommand.Settings.DEFAULTS.budget(),
                    AskCommand.Settings.DEFAULTS.retrieve(), retrieval, anchors, AskCommand.Settings.DEFAULTS.beam(), explain);
            IndexTree tree = retrieval.needsTree() ? session.tree() : null;
            AskCommand.answer(session.graph(), session.bm25(), tree, question, settings, out);
        });
    }

    private Callable<String> impact(Map<?, ?> arguments) {
        String symbol = requiredString(arguments, "symbol");
        int depth = optionalInt(arguments, "depth", 4, 1, 10);
        boolean risk = optionalBoolean(arguments, "risk", false);
        return () -> withCurrentSession((session, out) ->
                ImpactCommand.report(session.graph(), resolve(session.graph(), symbol, out), depth, risk, out));
    }

    private Callable<String> context(Map<?, ?> arguments) {
        String symbol = requiredString(arguments, "symbol");
        int depth = optionalInt(arguments, "depth", 3, 1, 10);
        return () -> withCurrentSession((session, out) -> {
            GraphNode subject = resolve(session.graph(), symbol, out);
            ContextPacket packet = GraphQueries.context(session.graph(), subject, depth);
            out.printf("Context packet for %s: callers=%d endpoints=%d dependencies=%d evidence=%d%n%n",
                    subject.id(), packet.callers().size(), packet.endpoints().size(),
                    packet.dependencies().size(), packet.evidence().size());
            out.print(GraphJsonWriter.writeContext(packet));
        });
    }

    private Callable<String> navigateStart(Map<?, ?> arguments) {
        String question = requiredString(arguments, "question");
        return () -> withCurrentSession((session, out) -> {
            NavigationSession.State state = NavigationSession.start(session.tree(), question);
            String id = "descent-" + nextDescent++;
            descents.put(id, state);
            out.printf("descent_id: %s%n%n", id);
            out.print(NavigationSession.packet(session.tree(), state, REPLY_BY_TOOL));
        });
    }

    private Callable<String> navigateChoose(Map<?, ?> arguments) {
        String id = requiredString(arguments, "descent_id");
        List<String> chosen = requiredStrings(arguments, "chosen");
        return () -> withCurrentSession((session, out) -> {
            NavigationSession.State state = descents.get(id);
            if (state == null) {
                throw new IllegalArgumentException("no descent " + id + " is open. Descents are discarded when the "
                        + "source changes, because their cards describe the previous graph, and the oldest are "
                        + "discarded past " + MAX_DESCENTS + ". Start again with navigate_start.");
            }
            IndexTree tree = session.tree();
            NavigationSession.State next = NavigationSession.advance(tree, state, chosen);
            if (!next.complete()) {
                descents.put(id, next);
                out.printf("descent_id: %s%n%n", id);
                out.print(NavigationSession.packet(tree, next, REPLY_BY_TOOL));
                return;
            }
            // Finished: answer from where the descent landed, through the same verification gate
            // as `ask`. The assistant chose where to look; the graph decides what can be said.
            descents.remove(id);
            CodeGraph graph = session.graph();
            List<GraphNode> anchors = new ArrayList<>();
            for (String graphId : NavigationSession.anchorGraphIds(tree, next)) graph.node(graphId).ifPresent(anchors::add);
            out.printf("DESCENT COMPLETE after %d step(s): %d anchor(s)%n%n", next.step(), anchors.size());
            AskCommand.answerFrom(graph, anchors, next.question(), AskCommand.Settings.DEFAULTS.budget(), out);
        });
    }

    private String status() throws IOException {
        return render(out -> {
            AnalysisSession session;
            try {
                session = opening.get();
            } catch (ExecutionException | InterruptedException failed) {
                out.println("state: failed to open");
                out.println("reason: " + explain(failed instanceof ExecutionException e ? e.getCause() : failed));
                return;
            }
            CodeGraph graph = session.graph();
            out.println("state: ready");
            out.println("repository: " + session.request().repository().toAbsolutePath().normalize());
            out.println("source files: " + session.fingerprint().files());
            out.println("fingerprint: " + session.fingerprint().value().substring(0, 12));
            out.println("nodes: " + graph.nodes().size() + ", edges: " + graph.edges().size());
            out.println("stale: " + session.stale() + " (the next tool call rebuilds if so)");
            out.println("open descents: " + descents.size());
        });
    }

    /** Work that needs a session that describes the source as it is now. */
    @FunctionalInterface
    private interface SessionWork {
        void run(AnalysisSession session, PrintStream out) throws Exception;
    }

    private String withCurrentSession(SessionWork work) throws Exception {
        AnalysisSession session;
        try {
            session = opening.get();
        } catch (ExecutionException failed) {
            throw new IllegalStateException("the repository could not be analyzed: " + explain(failed.getCause()), failed.getCause());
        }
        long started = System.nanoTime();
        boolean rebuilt;
        try {
            rebuilt = session.refresh();
        } catch (IOException | RuntimeException failed) {
            // The session still holds the previous graph, and still reports it stale. Answering
            // from it would describe code that has since changed, so this refuses instead.
            throw new IllegalStateException("the source changed and could not be re-analyzed, so no answer is "
                    + "given from the previous graph: " + explain(failed), failed);
        }
        if (rebuilt) descents.clear();
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        return render(out -> {
            if (rebuilt) {
                out.printf("(source changed since the last call; re-analyzed in %d ms, and any open descents "
                        + "were discarded)%n%n", elapsed);
            }
            work.run(session, out);
        });
    }

    @FunctionalInterface
    private interface Rendering {
        void run(PrintStream out) throws Exception;
    }

    private static String render(Rendering rendering) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            rendering.run(out);
        } catch (IOException | RuntimeException rethrown) {
            throw rethrown;
        } catch (Exception other) {
            throw new IOException(other);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static GraphNode resolve(CodeGraph graph, String symbol, PrintStream out) {
        // Warnings about an ambiguous symbol go into the answer: the client never sees stderr.
        return Symbols.resolve(graph, symbol, out).orElseThrow(() ->
                new IllegalArgumentException("no symbol matched \"" + symbol + "\". Try a simple class or "
                        + "method name, or a suffix of a graph id such as #authorize/0."));
    }

    private static String explain(Throwable failure) {
        if (failure instanceof OutOfMemoryError) {
            return "ran out of heap while analyzing. Restart the server with more memory, for example "
                    + "JAVA_OPTS=-Xmx1536m - a repository of about 1,400 source files needs 1 GB to analyze.";
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    // --------------------------------------------------------------------------- arguments

    private static final class InvalidParams extends RuntimeException {
        InvalidParams(String message) { super(message); }
    }

    private static String requiredString(Map<?, ?> arguments, String name) {
        if (!(arguments.get(name) instanceof String value) || value.isBlank()) {
            throw new InvalidParams("`" + name + "` is required and must be a non-empty string");
        }
        return value;
    }

    private static List<String> requiredStrings(Map<?, ?> arguments, String name) {
        if (!(arguments.get(name) instanceof List<?> values) || values.isEmpty()) {
            throw new InvalidParams("`" + name + "` is required and must be a non-empty array of ids");
        }
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String s) || s.isBlank()) throw new InvalidParams("`" + name + "` must contain only strings");
            result.add(s.trim());
        }
        return result;
    }

    private static int optionalInt(Map<?, ?> arguments, String name, int fallback, int min, int max) {
        Object value = arguments.get(name);
        if (value == null) return fallback;
        if (!(value instanceof Long l) || l < min || l > max) {
            throw new InvalidParams("`" + name + "` must be an integer from " + min + " to " + max);
        }
        return l.intValue();
    }

    private static boolean optionalBoolean(Map<?, ?> arguments, String name, boolean fallback) {
        Object value = arguments.get(name);
        if (value == null) return fallback;
        if (!(value instanceof Boolean b)) throw new InvalidParams("`" + name + "` must be true or false");
        return b;
    }

    private static RetrievalMode optionalEnum(Map<?, ?> arguments, String name, RetrievalMode fallback, List<String> allowed) {
        Object value = arguments.get(name);
        if (value == null) return fallback;
        if (!(value instanceof String s) || !allowed.contains(s)) {
            throw new InvalidParams("`" + name + "` must be one of " + String.join(", ", allowed));
        }
        return RetrievalMode.valueOf(s);
    }

    // ---------------------------------------------------------------------------- messages

    private static String response(Object id, Object result) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("result", result);
        return JsonCodec.write(message);
    }

    private static String error(Object id, int code, String text) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("error", Map.of("code", code, "message", text));
        return JsonCodec.write(message);
    }

    private static Map<String, Object> result(String text, boolean isError) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", text)));
        result.put("isError", isError);
        return result;
    }

    @Override public void close() {
        worker.shutdownNow();
    }

    /** The tools, as the client sees them. */
    static final class Tools {
        private Tools() { }

        /** Dense modes need a configured embedding model, which a server started from a jar does not have. */
        static final List<String> RETRIEVAL = List.of("HYBRID", "TREE", "BM25");

        static final List<String> NAMES = List.of("ask", "impact", "context", "navigate_start", "navigate_choose", "status");

        static List<Map<String, Object>> definitions() {
            return List.of(
                    tool("ask", """
                            Answer a question about this repository in plain language. Retrieves the \
                            symbols the question is about, walks their relationships, and returns an \
                            answer whose every claim cites a file and line; anything the graph cannot \
                            support is withheld rather than guessed.""",
                            properties(
                                    "question", Map.of("type", "string", "description", "The question, in plain language."),
                                    "anchors", Map.of("type", "integer", "minimum", 1, "maximum", 10, "default", 1,
                                            "description", "How many symbols the answer may rest on. Raise it for questions with more than one answer."),
                                    "retrieval", Map.of("type", "string", "enum", RETRIEVAL, "default", "HYBRID",
                                            "description", "HYBRID combines tree navigation with flat ranking and is never worse than either on the benchmark corpus."),
                                    "explain", Map.of("type", "boolean", "default", false,
                                            "description", "Include the descent: what was on each card and which branch won.")),
                            List.of("question")),
                    tool("impact", """
                            What is affected if a symbol changes: its direct and transitive dependents, \
                            each with the file and line of the relationship that connects them.""",
                            properties(
                                    "symbol", Map.of("type", "string", "description", "A class or method name, a full graph id, or an id suffix such as #authorize/0."),
                                    "depth", Map.of("type", "integer", "minimum", 1, "maximum", 10, "default", 4),
                                    "risk", Map.of("type", "boolean", "default", false, "description", "Include an explained change-risk score.")),
                            List.of("symbol")),
                    tool("context", """
                            The minimum evidence needed to reason about a symbol: its callers, the \
                            endpoints that reach it, its dependencies, and only the edges that support \
                            those, as JSON.""",
                            properties(
                                    "symbol", Map.of("type", "string", "description", "A class or method name, a full graph id, or an id suffix."),
                                    "depth", Map.of("type", "integer", "minimum", 1, "maximum", 10, "default", 3)),
                            List.of("symbol")),
                    tool("navigate_start", """
                            Start steering a search yourself. Returns the top of the repository's table \
                            of contents as cards; choose where to look with navigate_choose. Use this \
                            when `ask` lands in the wrong place, or when you want to decide the branches.""",
                            properties("question", Map.of("type", "string", "description", "The question the descent is for.")),
                            List.of("question")),
                    tool("navigate_choose", """
                            Choose ids from the cards of an open descent. Returns the next level's cards, \
                            or, once the descent finishes, the verified answer from where it landed. Ids \
                            that were not on the cards are rejected rather than followed.""",
                            properties(
                                    "descent_id", Map.of("type", "string", "description", "The descent_id navigate_start returned."),
                                    "chosen", Map.of("type", "array", "items", Map.of("type", "string"), "minItems", 1,
                                            "description", "Ids from the cards just presented. Choose a card's own id to stop there.")),
                            List.of("descent_id", "chosen")),
                    tool("status", """
                            Which repository is loaded, how large the graph is, and whether the source \
                            has changed since it was analyzed.""",
                            properties(), List.of()));
        }

        private static Map<String, Object> tool(String name, String description, Map<String, Object> properties, List<String> required) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            if (!required.isEmpty()) schema.put("required", required);
            schema.put("additionalProperties", false);
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", name);
            tool.put("description", description);
            tool.put("inputSchema", schema);
            return tool;
        }

        private static Map<String, Object> properties(Object... pairs) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
            return result;
        }
    }
}

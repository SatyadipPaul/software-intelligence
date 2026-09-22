package io.softwareintelligence.queryengine;

import io.softwareintelligence.indextree.IndexCards;
import io.softwareintelligence.indextree.IndexNode;
import io.softwareintelligence.indextree.IndexTree;
import io.softwareintelligence.model.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The file contract between an assistant and a tree descent.
 *
 * <p>The product does not call a model. It writes the cards at the current frontier and reads back
 * the ids the assistant chose, one level at a time, exactly as the enrichment loop writes work
 * packets and reads back claims. Whatever does the choosing is the operator's business.
 *
 * <p>What makes an unattended run safe is not a prompt: it is that a choice is checked against the
 * set that was presented. An id from elsewhere in the tree is rejected rather than followed, and an
 * assistant is never in a position to assert anything about the code, because a navigation choice
 * names a place to look and nothing more. A bad descent costs recall, which the benchmark measures.
 * It cannot put a wrong fact into an answer.
 */
public final class NavigationSession {

    /** Where a descent has got to. Persisted between invocations, since each level is a round trip. */
    public record State(String question, String fingerprint, List<String> frontier,
                        List<String> anchors, List<String> visited, int step) {

        public boolean complete() { return frontier.isEmpty(); }
    }

    private NavigationSession() { }

    public static State start(IndexTree tree, String question) {
        return new State(question, tree.fingerprint(), List.of(tree.root().id()), List.of(), List.of(), 0);
    }

    /**
     * The ids a choice may name at this step: the children of every card at the frontier, plus each
     * card's own id, which is how an assistant says "the answer is this branch, do not descend".
     */
    public static List<String> presented(IndexTree tree, State state) {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : state.frontier()) {
            IndexNode node = tree.node(id).orElse(null);
            if (node == null) continue;
            if (!node.anchor().isBlank()) ids.add(node.id());
            ids.addAll(node.children());
        }
        return List.copyOf(ids);
    }

    /**
     * Advances one level.
     *
     * @throws IllegalArgumentException if a chosen id was not on the cards this step presented
     */
    public static State advance(IndexTree tree, State state, List<String> chosen) {
        if (state.complete()) throw new IllegalStateException("this descent has already finished");
        if (chosen.isEmpty()) throw new IllegalArgumentException("no ids were chosen; choose at least one from the cards");
        List<String> presented = presented(tree, state);
        List<String> unknown = chosen.stream().filter(id -> !presented.contains(id)).toList();
        if (!unknown.isEmpty()) {
            // A heading is printed on its card like any other id, so "not on the cards" would be
            // false for it and the reader would reasonably try it again. Say what is actually true.
            List<String> headings = unknown.stream().filter(state.frontier()::contains).toList();
            List<String> absent = unknown.stream().filter(id -> !headings.contains(id)).toList();
            List<String> reasons = new ArrayList<>();
            if (!headings.isEmpty()) {
                reasons.add(String.join(", ", headings) + " " + (headings.size() == 1 ? "is a heading" : "are headings")
                        + " with no symbol behind it, so there is nothing to stop at; choose one of its children");
            }
            if (!absent.isEmpty()) {
                reasons.add("these ids were not on the cards presented at step " + state.step()
                        + ", so they were not followed: " + String.join(", ", absent));
            }
            throw new IllegalArgumentException(String.join("; ", reasons));
        }

        Set<String> anchors = new LinkedHashSet<>(state.anchors());
        Set<String> visited = new LinkedHashSet<>(state.visited());
        Set<String> frontier = new LinkedHashSet<>();
        visited.addAll(state.frontier());
        for (String id : chosen) {
            IndexNode node = tree.node(id).orElseThrow();
            // Choosing a card that was already open means "stop here"; choosing a leaf means the
            // same thing, because there is nowhere left to go.
            if (state.frontier().contains(id) || node.leaf()) {
                if (!node.anchor().isBlank()) anchors.add(node.id());
                continue;
            }
            if (!visited.contains(id)) frontier.add(id);
        }
        return new State(state.question(), state.fingerprint(), List.copyOf(frontier),
                List.copyOf(anchors), List.copyOf(visited), state.step() + 1);
    }

    /** The graph nodes a finished descent anchors on. */
    public static List<String> anchorGraphIds(IndexTree tree, State state) {
        List<String> ids = new ArrayList<>();
        for (String id : state.anchors()) {
            tree.node(id).map(IndexNode::anchor).filter(anchor -> !anchor.isBlank())
                    .filter(anchor -> !ids.contains(anchor)).ifPresent(ids::add);
        }
        return List.copyOf(ids);
    }

    /**
     * The packet an assistant answers: the rules, the question, and the cards at this frontier.
     *
     * <p>The rules are repeated at every step on purpose. Instructions given once at the top of a
     * long session stop being followed, and this session is one exchange per level.
     */
    /** How a reader in a chat window answers: with the choice as JSON, and nothing else. */
    public static final String REPLY_AS_JSON = """
            Answer with only this JSON, and nothing else:

            ```json
            {"chosen": ["<id>", "<id>"]}
            ```
            """;

    public static String packet(IndexTree tree, State state) {
        return packet(tree, state, REPLY_AS_JSON);
    }

    /**
     * The cards for this step, with the reader told how to reply.
     *
     * <p>The rules are fixed; the reply is not. A person pasting cards into a chat needs the answer
     * back as JSON they can copy. An assistant calling a tool needs to be told which tool, and would
     * be misled by an instruction to answer with JSON and nothing else.
     */
    public static String packet(IndexTree tree, State state, String replyInstruction) {
        StringBuilder text = new StringBuilder();
        text.append("# Navigation step ").append(state.step()).append("\n\n");
        text.append("QUESTION: ").append(state.question()).append("\n\n");
        text.append("""
                ## Rules

                You are navigating a table of contents of a code repository to decide *where to look*.
                You are not answering the question and not describing any code.

                - Choose only ids that appear on the cards below. Any other id is rejected, not followed.
                - Choose a child id to descend into it.
                - Choose a card's own id to stop there, when that entry is itself the answer.
                - Choose more than one when the question has more than one answer.
                - Prefer the entry a reader would open, not the one whose words look most similar.

                """);
        text.append(replyInstruction);
        text.append("\n## Cards\n");
        for (String id : state.frontier()) {
            tree.node(id).ifPresent(node -> text.append('\n').append(IndexCards.render(tree, node)));
        }
        if (!state.anchors().isEmpty()) {
            text.append("\n## Already anchored\n\n");
            state.anchors().forEach(anchor -> text.append("  ").append(anchor).append('\n'));
        }
        return text.toString();
    }

    // ---------------------------------------------------------------- persistence

    public static String write(State state) {
        return "{\n  \"question\": \"" + Json.quote(state.question()) + "\",\n  \"graphFingerprint\": \""
                + Json.quote(state.fingerprint()) + "\",\n  \"step\": " + state.step()
                + ",\n  \"frontier\": " + array(state.frontier())
                + ",\n  \"anchors\": " + array(state.anchors())
                + ",\n  \"visited\": " + array(state.visited()) + "\n}\n";
    }

    public static void write(State state, Path destination) throws IOException {
        if (destination.getParent() != null) Files.createDirectories(destination.getParent());
        Files.writeString(destination, write(state), StandardCharsets.UTF_8);
    }

    public static State read(Path source) throws IOException {
        String json = Files.readString(source, StandardCharsets.UTF_8);
        try {
            return new State(field(json, "question"), field(json, "graphFingerprint"),
                    list(json, "frontier"), list(json, "anchors"), list(json, "visited"),
                    Integer.parseInt(literal(json, "step")));
        } catch (RuntimeException malformed) {
            throw new IOException(source + " is not a navigation session, or is truncated."
                    + " Start a new one with `repo-intel navigate`.", malformed);
        }
    }

    /** Refuses a session started against a different graph, for the same reason a stale tree is refused. */
    public static void requireCurrent(State state, IndexTree tree) {
        if (!state.fingerprint().equals(tree.fingerprint())) {
            throw new IllegalStateException("this session was started against a different graph;"
                    + " rebuild the tree and start a new descent");
        }
    }

    private static String array(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) json.append(", ");
            json.append('"').append(Json.quote(values.get(i))).append('"');
        }
        return json.append(']').toString();
    }

    private static String field(String json, String name) {
        int marker = json.indexOf('"' + name + '"');
        if (marker < 0) return "";
        int start = json.indexOf('"', json.indexOf(':', marker) + 1);
        StringBuilder text = new StringBuilder();
        for (int i = start + 1; i < json.length(); i++) {
            char character = json.charAt(i);
            if (character == '\\') { text.append(unescape(json.charAt(++i))); continue; }
            if (character == '"') break;
            text.append(character);
        }
        return text.toString();
    }

    private static char unescape(char escaped) {
        return switch (escaped) {
            case 'n' -> '\n';
            case 't' -> '\t';
            case 'r' -> '\r';
            default -> escaped;
        };
    }

    private static String literal(String json, String name) {
        int marker = json.indexOf('"' + name + '"');
        if (marker < 0) return "0";
        int start = json.indexOf(':', marker) + 1;
        StringBuilder text = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char character = json.charAt(i);
            if (Character.isWhitespace(character) && text.isEmpty()) continue;
            if (",}\n".indexOf(character) >= 0) break;
            text.append(character);
        }
        return text.toString().trim();
    }

    private static List<String> list(String json, String name) {
        int marker = json.indexOf('"' + name + '"');
        if (marker < 0) return List.of();
        int start = json.indexOf('[', marker);
        int end = json.indexOf(']', start);
        if (start < 0 || end < 0) return List.of();
        List<String> values = new ArrayList<>();
        String body = json.substring(start + 1, end);
        for (String part : body.split(",")) {
            String trimmed = part.trim();
            if (trimmed.length() >= 2 && trimmed.startsWith("\"")) values.add(trimmed.substring(1, trimmed.length() - 1));
        }
        return List.copyOf(values);
    }

    /** Reads the {@code {"chosen": [...]}} document an assistant answers with. */
    public static List<String> readChoices(Path source) throws IOException {
        return list(Files.readString(source, StandardCharsets.UTF_8), "chosen");
    }
}

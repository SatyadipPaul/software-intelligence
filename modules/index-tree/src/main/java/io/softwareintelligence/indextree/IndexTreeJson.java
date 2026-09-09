package io.softwareintelligence.indextree;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads and writes the pinned tree file.
 *
 * <p>Like the graph writer this is a targeted parser for one document shape rather than a general
 * JSON library, because the product ships with no runtime dependencies and a reader that only
 * accepts our own output is easier to keep honest than a permissive one.
 */
public final class IndexTreeJson {
    private IndexTreeJson() { }

    public static String write(IndexTree tree) {
        StringBuilder json = new StringBuilder("{\n  \"version\": \"" + IndexTree.SCHEMA_VERSION + "\",\n  \"graphFingerprint\": \"")
                .append(Json.quote(tree.fingerprint())).append("\",\n  \"nodes\": [");
        List<IndexNode> nodes = tree.nodes();
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) json.append(',');
            json.append("\n    ").append(node(nodes.get(i)));
        }
        return json.append("\n  ]\n}\n").toString();
    }

    public static void write(IndexTree tree, Path destination) throws IOException {
        if (destination.getParent() != null) Files.createDirectories(destination.getParent());
        Files.writeString(destination, write(tree), StandardCharsets.UTF_8);
    }

    /** Reads a tree file, refusing one written by a different schema. */
    public static IndexTree read(Path source) throws IOException {
        String json = Files.readString(source, StandardCharsets.UTF_8);
        Parser parser = new Parser(json);
        String version = parser.stringField("version");
        if (version.isBlank()) {
            throw new IOException(source + " is not an index tree: no \"version\" field found. "
                    + "Expected a file written by `repo-intel index`.");
        }
        if (!IndexTree.SCHEMA_VERSION.equals(version)) {
            throw new IOException("index tree " + source + " uses schema " + version + " but this build writes "
                    + IndexTree.SCHEMA_VERSION + "; rebuild it with `repo-intel index`");
        }
        try {
            return new IndexTree(parser.stringField("graphFingerprint"), parser.nodes());
        } catch (RuntimeException malformed) {
            throw new IOException(source + " is an index tree but could not be read: it looks truncated or edited."
                    + " Rebuild it with `repo-intel index`.", malformed);
        }
    }

    /**
     * Refuses a tree that was built from a different graph. An anchor from a stale tree points at a
     * symbol that may have moved or been deleted, which is exactly the kind of quiet wrongness the
     * rest of this product refuses to produce.
     */
    public static void requireCurrent(IndexTree tree, CodeGraph graph) {
        String actual = GraphFingerprint.of(graph);
        if (!actual.equals(tree.fingerprint())) {
            throw new IllegalStateException("this index tree was built from a different graph (tree "
                    + shorten(tree.fingerprint()) + ", graph " + shorten(actual)
                    + "); rebuild it with `repo-intel index`");
        }
    }

    private static String shorten(String fingerprint) {
        return fingerprint.length() <= 12 ? fingerprint : fingerprint.substring(0, 12);
    }

    private static String node(IndexNode node) {
        return "{\"id\":\"" + Json.quote(node.id()) + "\",\"kind\":\"" + node.kind() + "\",\"name\":\"" + Json.quote(node.name())
                + "\",\"graphIds\":" + array(node.graphIds()) + ",\"children\":" + array(node.children())
                + ",\"facts\":" + facts(node.facts()) + "}";
    }

    private static String array(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) json.append(',');
            json.append('"').append(Json.quote(values.get(i))).append('"');
        }
        return json.append(']').toString();
    }

    private static String facts(Map<String, String> values) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append('"').append(Json.quote(entry.getKey())).append("\":\"").append(Json.quote(entry.getValue())).append('"');
        }
        return json.append('}').toString();
    }

    /** A cursor over the one document shape this class writes. */
    private static final class Parser {
        private final String json;
        private int cursor;

        private Parser(String json) { this.json = json; }

        private String stringField(String name) {
            int marker = json.indexOf('"' + name + '"');
            if (marker < 0) return "";
            cursor = json.indexOf(':', marker) + 1;
            skipWhitespace();
            return readString();
        }

        private List<IndexNode> nodes() {
            List<IndexNode> parsed = new ArrayList<>();
            int marker = json.indexOf("\"nodes\"");
            if (marker < 0) return parsed;
            cursor = json.indexOf('[', marker) + 1;
            while (cursor < json.length()) {
                skipWhitespace();
                char character = json.charAt(cursor);
                if (character == ']') break;
                if (character == ',') { cursor++; continue; }
                if (character != '{') break;
                parsed.add(readNode());
            }
            return parsed;
        }

        private IndexNode readNode() {
            String id = "";
            String kind = "";
            String name = "";
            List<String> graphIds = List.of();
            List<String> children = List.of();
            Map<String, String> facts = Map.of();
            cursor++;
            while (cursor < json.length()) {
                skipWhitespace();
                char character = json.charAt(cursor);
                if (character == '}') { cursor++; break; }
                if (character == ',') { cursor++; continue; }
                String key = readString();
                skipWhitespace();
                cursor++;
                skipWhitespace();
                switch (key) {
                    case "id" -> id = readString();
                    case "kind" -> kind = readString();
                    case "name" -> name = readString();
                    case "graphIds" -> graphIds = readArray();
                    case "children" -> children = readArray();
                    case "facts" -> facts = readFacts();
                    default -> skipValue();
                }
            }
            return new IndexNode(id, IndexKind.valueOf(kind), name, graphIds, children, facts);
        }

        private List<String> readArray() {
            List<String> values = new ArrayList<>();
            cursor++;
            while (cursor < json.length()) {
                skipWhitespace();
                char character = json.charAt(cursor);
                if (character == ']') { cursor++; break; }
                if (character == ',') { cursor++; continue; }
                values.add(readString());
            }
            return List.copyOf(values);
        }

        private Map<String, String> readFacts() {
            Map<String, String> values = new TreeMap<>();
            cursor++;
            while (cursor < json.length()) {
                skipWhitespace();
                char character = json.charAt(cursor);
                if (character == '}') { cursor++; break; }
                if (character == ',') { cursor++; continue; }
                String key = readString();
                skipWhitespace();
                cursor++;
                skipWhitespace();
                values.put(key, readString());
            }
            return Map.copyOf(values);
        }

        private void skipValue() {
            skipWhitespace();
            char character = json.charAt(cursor);
            if (character == '"') { readString(); return; }
            if (character == '[') { readArray(); return; }
            if (character == '{') { readFacts(); return; }
            while (cursor < json.length() && ",}] \n\r\t".indexOf(json.charAt(cursor)) < 0) cursor++;
        }

        private String readString() {
            StringBuilder text = new StringBuilder();
            cursor++;
            while (cursor < json.length()) {
                char character = json.charAt(cursor++);
                if (character == '"') return text.toString();
                if (character != '\\') { text.append(character); continue; }
                char escaped = json.charAt(cursor++);
                switch (escaped) {
                    case 'n' -> text.append('\n');
                    case 'r' -> text.append('\r');
                    case 't' -> text.append('\t');
                    case 'b' -> text.append('\b');
                    case 'f' -> text.append('\f');
                    case 'u' -> { text.append((char) Integer.parseInt(json.substring(cursor, cursor + 4), 16)); cursor += 4; }
                    default -> text.append(escaped);
                }
            }
            return text.toString();
        }

        private void skipWhitespace() {
            while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) cursor++;
        }
    }
}

package io.softwareintelligence.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads back exactly what {@link GraphJsonWriter} writes. It is a targeted parser for this one
 * document shape, not a general JSON library: the product ships with no runtime dependencies, and a
 * snapshot reader that only accepts our own output is easier to keep honest than a permissive one.
 */
final class GraphJsonReader {
    private final String json;
    private int cursor;

    private GraphJsonReader(String json) {
        this.json = json;
    }

    static String version(String json) {
        int value = topLevelValue(json, "version");
        if (value < 0) return "";
        GraphJsonReader reader = new GraphJsonReader(json);
        reader.cursor = value;
        reader.skipWhitespace();
        return reader.readString();
    }

    static CodeGraph read(String json) {
        GraphJsonReader reader = new GraphJsonReader(json);
        CodeGraph graph = new CodeGraph();
        int nodes = topLevelValue(json, "nodes");
        if (nodes >= 0) {
            reader.cursor = json.indexOf('[', nodes) + 1;
            reader.eachObject(object -> graph.upsertNode(new GraphNode(
                    object.get("id"), EntityKind.valueOf(object.get("kind")), object.get("name"),
                    attributes(object), provenance(object)), true));
        }
        int edges = topLevelValue(json, "edges");
        if (edges >= 0) {
            reader.cursor = json.indexOf('[', edges) + 1;
            reader.eachObject(object -> graph.addEdge(new GraphEdge(
                    object.get("from"), object.get("to"), RelationKind.valueOf(object.get("kind")),
                    attributes(object), provenance(object))));
        }
        return graph;
    }

    /**
     * Finds where a top-level key's value starts, by walking the document's structure.
     *
     * <p>Searching the text for {@code "edges"} instead - which is what this did - finds the first
     * place those characters appear anywhere, including inside a node's own name. Analyzing this
     * repository produces a method called {@code edges}, whose node is written as
     * {@code "name":"edges"} thousands of lines above the real edges array; every graph file it
     * wrote then read back with its edges silently missing, and impact analysis on such a file
     * answered "nothing is affected" rather than failing. Strings are skipped whole here, so their
     * contents can never be mistaken for a key.
     *
     * @return the index just after the key's colon, or -1 when the document has no such top-level key
     */
    private static int topLevelValue(String json, String key) {
        int depth = 0;
        for (int i = 0; i < json.length(); i++) {
            char character = json.charAt(i);
            if (character == '{' || character == '[') { depth++; continue; }
            if (character == '}' || character == ']') { depth--; continue; }
            if (character != '"') continue;
            int end = i + 1;
            while (end < json.length() && json.charAt(end) != '"') {
                if (json.charAt(end) == '\\') end++;
                end++;
            }
            if (depth == 1 && end == i + 1 + key.length() && json.regionMatches(i + 1, key, 0, key.length())) {
                int colon = json.indexOf(':', end);
                if (colon > 0) return colon + 1;
            }
            i = end;
        }
        return -1;
    }

    private static Map<String, String> attributes(Map<String, String> object) {
        Map<String, String> attributes = new LinkedHashMap<>();
        object.forEach((key, value) -> {
            if (key.startsWith("attributes.")) attributes.put(key.substring("attributes.".length()), value);
        });
        return Map.copyOf(attributes);
    }

    private static Provenance provenance(Map<String, String> object) {
        return new Provenance(object.getOrDefault("provenance.resolver", ""),
                Double.parseDouble(object.getOrDefault("provenance.confidence", "0")),
                object.getOrDefault("provenance.file", ""),
                Integer.parseInt(object.getOrDefault("provenance.line", "0")),
                Integer.parseInt(object.getOrDefault("provenance.column", "0")));
    }

    /** Walks the objects of the array the cursor sits inside, flattening one level of nesting. */
    private void eachObject(java.util.function.Consumer<Map<String, String>> consumer) {
        while (cursor < json.length()) {
            skipWhitespace();
            char character = json.charAt(cursor);
            if (character == ']') return;
            if (character == ',') { cursor++; continue; }
            if (character != '{') return;
            consumer.accept(readObject(""));
        }
    }

    private Map<String, String> readObject(String prefix) {
        Map<String, String> values = new LinkedHashMap<>();
        cursor++;
        while (cursor < json.length()) {
            skipWhitespace();
            char character = json.charAt(cursor);
            if (character == '}') { cursor++; return values; }
            if (character == ',') { cursor++; continue; }
            String key = readString();
            skipWhitespace();
            cursor++;
            skipWhitespace();
            if (json.charAt(cursor) == '{') {
                values.putAll(readObject(prefix + key + "."));
            } else if (json.charAt(cursor) == '"') {
                values.put(prefix + key, readString());
            } else {
                values.put(prefix + key, readLiteral());
            }
        }
        return values;
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

    private String readLiteral() {
        int start = cursor;
        while (cursor < json.length() && ",}] \n\r\t".indexOf(json.charAt(cursor)) < 0) cursor++;
        return json.substring(start, cursor);
    }

    private void skipWhitespace() {
        while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) cursor++;
    }
}

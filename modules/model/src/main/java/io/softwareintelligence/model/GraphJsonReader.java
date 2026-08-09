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
        int marker = json.indexOf("\"version\"");
        if (marker < 0) return "";
        GraphJsonReader reader = new GraphJsonReader(json);
        reader.cursor = json.indexOf(':', marker) + 1;
        reader.skipWhitespace();
        return reader.readString();
    }

    static CodeGraph read(String json) {
        GraphJsonReader reader = new GraphJsonReader(json);
        CodeGraph graph = new CodeGraph();
        reader.cursor = json.indexOf("\"nodes\"");
        if (reader.cursor >= 0) {
            reader.cursor = json.indexOf('[', reader.cursor) + 1;
            reader.eachObject(object -> graph.upsertNode(new GraphNode(
                    object.get("id"), EntityKind.valueOf(object.get("kind")), object.get("name"),
                    attributes(object), provenance(object)), true));
        }
        reader.cursor = json.indexOf("\"edges\"");
        if (reader.cursor >= 0) {
            reader.cursor = json.indexOf('[', reader.cursor) + 1;
            reader.eachObject(object -> graph.addEdge(new GraphEdge(
                    object.get("from"), object.get("to"), RelationKind.valueOf(object.get("kind")),
                    attributes(object), provenance(object))));
        }
        return graph;
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

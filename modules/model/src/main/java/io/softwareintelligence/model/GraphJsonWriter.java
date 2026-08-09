package io.softwareintelligence.model;

import java.util.Map;
import java.util.stream.Collectors;

/** Small dependency-free JSON writer for local graph snapshots and build plugins. */
public final class GraphJsonWriter {
    private GraphJsonWriter() { }

    public static String write(CodeGraph graph) {
        StringBuilder json = new StringBuilder("{\n  \"version\": \"0.1\",\n  \"nodes\": [");
        for (int i = 0; i < graph.nodes().size(); i++) {
            if (i > 0) json.append(',');
            json.append("\n    ").append(node(graph.nodes().get(i)));
        }
        json.append("\n  ],\n  \"edges\": [");
        for (int i = 0; i < graph.edges().size(); i++) {
            if (i > 0) json.append(',');
            json.append("\n    ").append(edge(graph.edges().get(i)));
        }
        return json.append("\n  ]\n}\n").toString();
    }

    private static String node(GraphNode value) {
        return "{\"id\":\"" + quote(value.id()) + "\",\"kind\":\"" + value.kind() + "\",\"name\":\"" + quote(value.name())
                + "\",\"attributes\":" + attributes(value.attributes()) + ",\"provenance\":" + provenance(value.provenance()) + "}";
    }

    private static String edge(GraphEdge value) {
        return "{\"from\":\"" + quote(value.from()) + "\",\"to\":\"" + quote(value.to()) + "\",\"kind\":\"" + value.kind()
                + "\",\"attributes\":" + attributes(value.attributes()) + ",\"provenance\":" + provenance(value.provenance()) + "}";
    }

    private static String provenance(Provenance value) {
        return "{\"resolver\":\"" + quote(value.resolver()) + "\",\"confidence\":" + value.confidence() + ",\"file\":\""
                + quote(value.file()) + "\",\"line\":" + value.line() + ",\"column\":" + value.column() + "}";
    }

    private static String attributes(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> "\"" + quote(entry.getKey()) + "\":\"" + quote(entry.getValue()) + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String quote(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}

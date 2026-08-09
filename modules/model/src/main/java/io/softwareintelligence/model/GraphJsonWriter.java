package io.softwareintelligence.model;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The one dependency-free JSON writer for local graph snapshots, context packets, and build plugins. */
public final class GraphJsonWriter {
    /**
     * Graph schema version. Bumped whenever node identity, edge identity, or the emitted field set
     * changes, so a stored snapshot can be told apart from one produced by a different analyzer.
     */
    public static final String SCHEMA_VERSION = "0.3";

    private GraphJsonWriter() { }

    public static String write(CodeGraph graph) {
        StringBuilder json = new StringBuilder("{\n  \"version\": \"" + SCHEMA_VERSION + "\",\n  \"nodes\": [");
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

    /** Writes the compact packet form: identity for nodes, and resolver evidence for each edge. */
    public static String writeContext(ContextPacket packet) {
        StringBuilder json = new StringBuilder("{\n  \"version\": \"" + SCHEMA_VERSION + "\",\n  \"subject\": ")
                .append(identity(packet.subject()));
        appendNodes(json, "callers", packet.callers());
        appendNodes(json, "endpoints", packet.endpoints());
        appendNodes(json, "dependencies", packet.dependencies());
        json.append(",\n  \"evidence\": [");
        for (int i = 0; i < packet.evidence().size(); i++) {
            if (i > 0) json.append(',');
            json.append("\n    ").append(evidence(packet.evidence().get(i)));
        }
        return json.append("\n  ]\n}\n").toString();
    }

    private static void appendNodes(StringBuilder json, String field, List<GraphNode> nodes) {
        json.append(",\n  \"").append(field).append("\": [");
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) json.append(',');
            json.append("\n    ").append(identity(nodes.get(i)));
        }
        json.append("\n  ]");
    }

    private static String node(GraphNode value) {
        return "{\"id\":\"" + Json.quote(value.id()) + "\",\"kind\":\"" + value.kind() + "\",\"name\":\"" + Json.quote(value.name())
                + "\",\"attributes\":" + attributes(value.attributes()) + ",\"provenance\":" + provenance(value.provenance()) + "}";
    }

    private static String identity(GraphNode value) {
        return "{\"id\":\"" + Json.quote(value.id()) + "\",\"kind\":\"" + value.kind() + "\",\"name\":\"" + Json.quote(value.name()) + "\"}";
    }

    private static String edge(GraphEdge value) {
        return "{\"from\":\"" + Json.quote(value.from()) + "\",\"to\":\"" + Json.quote(value.to()) + "\",\"kind\":\"" + value.kind()
                + "\",\"attributes\":" + attributes(value.attributes()) + ",\"provenance\":" + provenance(value.provenance()) + "}";
    }

    private static String evidence(GraphEdge value) {
        Provenance provenance = value.provenance();
        return "{\"from\":\"" + Json.quote(value.from()) + "\",\"to\":\"" + Json.quote(value.to()) + "\",\"kind\":\"" + value.kind()
                + "\",\"resolver\":\"" + Json.quote(provenance.resolver()) + "\",\"confidence\":" + provenance.confidence()
                + ",\"file\":\"" + Json.quote(provenance.file()) + "\",\"line\":" + provenance.line() + "}";
    }

    private static String provenance(Provenance value) {
        return "{\"resolver\":\"" + Json.quote(value.resolver()) + "\",\"confidence\":" + value.confidence() + ",\"file\":\""
                + Json.quote(value.file()) + "\",\"line\":" + value.line() + ",\"column\":" + value.column() + "}";
    }

    private static String attributes(Map<String, String> values) {
        return values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> "\"" + Json.quote(entry.getKey()) + "\":\"" + Json.quote(entry.getValue()) + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }
}

package io.softwareintelligence.visualization;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;

import java.util.Map;

/**
 * Interchange formats, so the graph is not trapped behind our own viewer. GraphML opens in Gephi,
 * yEd, and Cytoscape; DOT renders with Graphviz.
 *
 * <p>Both carry provenance through as attributes. A visualization that drops the resolver and
 * confidence would look like every edge is equally certain, which is the one impression this
 * product exists to avoid.
 */
public final class GraphExports {
    private GraphExports() { }

    public static String graphml(CodeGraph graph) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <key id="name" for="node" attr.name="name" attr.type="string"/>
                  <key id="kind" for="node" attr.name="kind" attr.type="string"/>
                  <key id="file" for="node" attr.name="file" attr.type="string"/>
                  <key id="line" for="node" attr.name="line" attr.type="int"/>
                  <key id="relation" for="edge" attr.name="relation" attr.type="string"/>
                  <key id="resolver" for="edge" attr.name="resolver" attr.type="string"/>
                  <key id="confidence" for="edge" attr.name="confidence" attr.type="double"/>
                  <key id="evidence" for="edge" attr.name="evidence" attr.type="string"/>
                  <graph id="repository" edgedefault="directed">
                """);
        for (GraphNode node : graph.nodes()) {
            xml.append("    <node id=\"").append(xml(node.id())).append("\">\n")
                    .append("      <data key=\"name\">").append(xml(node.name())).append("</data>\n")
                    .append("      <data key=\"kind\">").append(node.kind()).append("</data>\n")
                    .append("      <data key=\"file\">").append(xml(node.provenance().file())).append("</data>\n")
                    .append("      <data key=\"line\">").append(node.provenance().line()).append("</data>\n")
                    .append("    </node>\n");
        }
        int index = 0;
        for (GraphEdge edge : graph.edges()) {
            xml.append("    <edge id=\"e").append(index++).append("\" source=\"").append(xml(edge.from()))
                    .append("\" target=\"").append(xml(edge.to())).append("\">\n")
                    .append("      <data key=\"relation\">").append(edge.kind()).append("</data>\n")
                    .append("      <data key=\"resolver\">").append(xml(edge.provenance().resolver())).append("</data>\n")
                    .append("      <data key=\"confidence\">").append(edge.provenance().confidence()).append("</data>\n")
                    .append("      <data key=\"evidence\">").append(xml(edge.provenance().file() + ":" + edge.provenance().line()))
                    .append("</data>\n    </edge>\n");
        }
        return xml.append("  </graph>\n</graphml>\n").toString();
    }

    public static String dot(CodeGraph graph) {
        StringBuilder dot = new StringBuilder("digraph repository {\n  rankdir=LR;\n  node [shape=box, fontname=\"Helvetica\"];\n");
        for (GraphNode node : graph.nodes()) {
            dot.append("  \"").append(escape(node.id())).append("\" [label=\"").append(escape(node.name()))
                    .append("\\n").append(node.kind()).append("\"];\n");
        }
        for (GraphEdge edge : graph.edges()) {
            // A relationship that is not compiler-proven is drawn dashed, so uncertainty survives
            // the trip into someone else's renderer.
            boolean proven = edge.provenance().confidence() >= 0.95;
            dot.append("  \"").append(escape(edge.from())).append("\" -> \"").append(escape(edge.to()))
                    .append("\" [label=\"").append(edge.kind()).append("\"")
                    .append(proven ? "" : ", style=dashed").append("];\n");
        }
        return dot.append("}\n").toString();
    }

    /** Cytoscape.js element JSON, for anyone who already has that viewer wired up. */
    public static String cytoscape(CodeGraph graph) {
        StringBuilder json = new StringBuilder("{\n  \"elements\": {\n    \"nodes\": [");
        for (int i = 0; i < graph.nodes().size(); i++) {
            GraphNode node = graph.nodes().get(i);
            if (i > 0) json.append(',');
            json.append("\n      {\"data\": {\"id\": \"").append(io.softwareintelligence.model.Json.quote(node.id()))
                    .append("\", \"label\": \"").append(io.softwareintelligence.model.Json.quote(node.name()))
                    .append("\", \"kind\": \"").append(node.kind())
                    .append("\", \"file\": \"").append(io.softwareintelligence.model.Json.quote(node.provenance().file()))
                    .append("\", \"line\": ").append(node.provenance().line()).append("}}");
        }
        json.append("\n    ],\n    \"edges\": [");
        for (int i = 0; i < graph.edges().size(); i++) {
            GraphEdge edge = graph.edges().get(i);
            if (i > 0) json.append(',');
            json.append("\n      {\"data\": {\"source\": \"").append(io.softwareintelligence.model.Json.quote(edge.from()))
                    .append("\", \"target\": \"").append(io.softwareintelligence.model.Json.quote(edge.to()))
                    .append("\", \"relation\": \"").append(edge.kind())
                    .append("\", \"resolver\": \"").append(io.softwareintelligence.model.Json.quote(edge.provenance().resolver()))
                    .append("\", \"confidence\": ").append(edge.provenance().confidence()).append("}}");
        }
        return json.append("\n    ]\n  }\n}\n").toString();
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

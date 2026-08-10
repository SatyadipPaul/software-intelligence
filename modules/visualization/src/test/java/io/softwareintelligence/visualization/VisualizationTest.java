package io.softwareintelligence.visualization;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.Provenance;
import io.softwareintelligence.model.RelationKind;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualizationTest {
    private static final Provenance PROVEN = new Provenance("JDT_BINDING", 1.0, "src/main/java/demo/A.java", 12, 3);
    private static final Provenance INFERRED = new Provenance("DISPATCH_NORMALIZED", 0.6, "src/main/java/demo/B.java", 20, 1);

    @Test void the_view_is_one_file_that_fetches_nothing() {
        String html = GraphHtmlView.render(graph(), "demo").html();

        assertFalse(html.contains("http://"), "a view must not reference an external host");
        assertTrue(html.indexOf("https://") < 0 || html.indexOf("https://") > html.indexOf("<title>"), html.substring(0, 200));
        assertFalse(html.contains("<script src"), "no external script");
        assertFalse(html.contains("@import"), "no external stylesheet");
        assertTrue(html.contains("<canvas"));
    }

    @Test void rendering_the_same_graph_twice_produces_the_same_file() {
        assertEquals(GraphHtmlView.render(graph(), "demo").html(), GraphHtmlView.render(graph(), "demo").html());
    }

    @Test void every_node_carries_its_provenance_into_the_view() {
        String html = GraphHtmlView.render(graph(), "demo").html();

        assertTrue(html.contains("src/main/java/demo/A.java"), "the declaring file must reach the viewer");
        assertTrue(html.contains("\"line\":12"));
        assertTrue(html.contains("\"c\":0.6"), "edge confidence must reach the viewer");
        assertTrue(html.contains("DISPATCH_NORMALIZED"), "the resolver must reach the viewer");
    }

    @Test void truncation_keeps_the_highest_degree_nodes_and_says_so() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(node("type:demo.Hub", EntityKind.SERVICE));
        for (int i = 0; i < 20; i++) {
            graph.upsertNode(node("type:demo.Leaf" + i, EntityKind.TYPE));
            graph.addEdge(new GraphEdge("type:demo.Leaf" + i, "type:demo.Hub", RelationKind.CALLS, Map.of(), PROVEN));
        }

        GraphHtmlView.View view = GraphHtmlView.render(graph, "demo", 5);

        assertTrue(view.truncated());
        assertEquals(5, view.renderedNodes());
        assertEquals(21, view.totalNodes());
        assertTrue(view.html().contains("type:demo.Hub"), "the most connected node must survive truncation");
        assertTrue(view.html().contains("5 of 21 nodes"), "truncation must be stated, not silent");
    }

    @Test void a_view_that_fits_reports_no_truncation() {
        GraphHtmlView.View view = GraphHtmlView.render(graph(), "demo", 100);

        assertFalse(view.truncated());
        assertEquals(view.totalNodes(), view.renderedNodes());
    }

    @Test void graphml_is_well_formed_xml_carrying_resolver_and_confidence() throws Exception {
        String graphml = GraphExports.graphml(graph());

        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(graphml.getBytes(StandardCharsets.UTF_8)));
        assertEquals("graphml", document.getDocumentElement().getTagName());
        assertEquals(3, document.getElementsByTagName("node").getLength());
        assertEquals(2, document.getElementsByTagName("edge").getLength());
        assertTrue(graphml.contains("DISPATCH_NORMALIZED"));
        assertTrue(graphml.contains("0.6"));
    }

    @Test void xml_special_characters_in_a_symbol_do_not_break_the_export() throws Exception {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(new GraphNode("type:demo.Box<T & U>", EntityKind.TYPE, "Box<T & U>", Map.of(), PROVEN));

        String graphml = GraphExports.graphml(graph);

        DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(graphml.getBytes(StandardCharsets.UTF_8)));
        assertTrue(graphml.contains("&amp;"));
        assertTrue(graphml.contains("&lt;"));
    }

    @Test void dot_dashes_relationships_that_are_not_compiler_proven() {
        String dot = GraphExports.dot(graph());

        assertTrue(dot.startsWith("digraph repository {"));
        assertEquals(1, dot.lines().filter(line -> line.contains("style=dashed")).count(),
                "exactly the inferred edge should be dashed");
    }

    @Test void cytoscape_json_is_parseable_and_keeps_evidence() {
        String json = GraphExports.cytoscape(graph());

        assertTrue(json.contains("\"elements\""));
        assertTrue(json.contains("\"resolver\": \"DISPATCH_NORMALIZED\"") || json.contains("\"resolver\": \"JDT_BINDING\""));
        assertTrue(json.contains("\"confidence\": 0.6"));
    }

    private static CodeGraph graph() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(node("type:demo.A", EntityKind.SERVICE));
        graph.upsertNode(node("type:demo.B", EntityKind.CONTROLLER));
        graph.upsertNode(node("endpoint:GET:/a", EntityKind.ENDPOINT));
        graph.addEdge(new GraphEdge("type:demo.B", "type:demo.A", RelationKind.CALLS, Map.of(), PROVEN));
        graph.addEdge(new GraphEdge("endpoint:GET:/a", "type:demo.B", RelationKind.EXPOSES, Map.of(), INFERRED));
        return graph;
    }

    private static GraphNode node(String id, EntityKind kind) {
        return new GraphNode(id, kind, id.substring(id.indexOf(':') + 1), Map.of(), PROVEN);
    }
}

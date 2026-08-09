package io.softwareintelligence.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphQueriesTest {
    private static final Provenance SOURCE = Provenance.syntax("Demo.java", 3, 1);

    @Test void an_exact_id_wins_over_every_other_tier() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(type("type:demo.PaymentService", "demo.PaymentService"));
        graph.upsertNode(type("PaymentService", "other"));

        assertEquals("PaymentService", GraphQueries.findSymbol(graph, "PaymentService").orElseThrow().id());
    }

    @Test void a_suffix_only_matches_on_an_identifier_boundary() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(type("type:demo.PaymentService", "demo.PaymentService"));

        assertTrue(GraphQueries.findSymbol(graph, "PaymentService").isPresent());
        assertTrue(GraphQueries.findSymbol(graph, "demo.PaymentService").isPresent());
        assertTrue(GraphQueries.findSymbol(graph, "Service").isEmpty(), "a bare fragment must not silently match a longer name");
    }

    @Test void an_ambiguous_query_reports_the_other_matches_and_picks_the_lowest_id() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(type("type:b.Order", "Order"));
        graph.upsertNode(type("type:a.Order", "Order"));

        GraphQueries.SymbolMatch match = GraphQueries.resolveSymbol(graph, "Order").orElseThrow();

        assertTrue(match.ambiguous());
        assertEquals("type:a.Order", match.node().id());
        assertEquals(List.of("type:b.Order"), match.alternatives().stream().map(GraphNode::id).toList());
    }

    @Test void a_unique_match_is_not_ambiguous() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(type("type:a.Order", "Order"));

        assertFalse(GraphQueries.resolveSymbol(graph, "Order").orElseThrow().ambiguous());
    }

    @Test void source_symbols_are_preferred_over_external_ones() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(new GraphNode("external:demo.Order", EntityKind.EXTERNAL_SYMBOL, "Order", Map.of(), SOURCE));
        graph.upsertNode(type("type:demo.Order", "Order"));

        assertEquals("type:demo.Order", GraphQueries.findSymbol(graph, "Order").orElseThrow().id());
    }

    @Test void impact_walks_declared_members_and_separates_direct_from_transitive() {
        CodeGraph graph = serviceCalledByControllerExposedAsEndpoint();

        ImpactReport report = GraphQueries.impact(graph, graph.node("type:demo.Service").orElseThrow(), 3);

        assertEquals(List.of("type:demo.Controller#handle()"), report.direct().stream().map(path -> path.target().id()).toList());
        assertEquals(List.of("endpoint:GET:/orders"), report.transitive().stream().map(path -> path.target().id()).toList());
    }

    @Test void every_impact_path_is_made_only_of_evidence_bearing_edges() {
        CodeGraph graph = serviceCalledByControllerExposedAsEndpoint();

        ImpactReport report = GraphQueries.impact(graph, graph.node("type:demo.Service").orElseThrow(), 3);

        List<ImpactReport.ImpactPath> all = new java.util.ArrayList<>(report.direct());
        all.addAll(report.transitive());
        assertFalse(all.isEmpty());
        for (ImpactReport.ImpactPath path : all) {
            assertFalse(path.evidence().isEmpty(), path.target().id() + " was reported with no evidence");
            for (GraphEdge edge : path.evidence()) {
                assertFalse(edge.provenance().file().isBlank(), edge + " has no source file");
                assertTrue(edge.provenance().line() > 0, edge + " has no source line");
            }
        }
    }

    @Test void depth_bounds_the_traversal() {
        CodeGraph graph = serviceCalledByControllerExposedAsEndpoint();

        ImpactReport report = GraphQueries.impact(graph, graph.node("type:demo.Service").orElseThrow(), 1);

        assertEquals(1, report.direct().size());
        assertTrue(report.transitive().isEmpty());
    }

    @Test void a_context_packet_is_smaller_than_the_graph_and_keeps_its_evidence() {
        CodeGraph graph = serviceCalledByControllerExposedAsEndpoint();

        ContextPacket packet = GraphQueries.context(graph, graph.node("type:demo.Service").orElseThrow(), 3);

        assertEquals(List.of("type:demo.Controller#handle()"), packet.callers().stream().map(GraphNode::id).toList());
        assertEquals(List.of("endpoint:GET:/orders"), packet.endpoints().stream().map(GraphNode::id).toList());
        assertFalse(packet.evidence().isEmpty());
        assertTrue(packet.evidence().size() < graph.edges().size() + 1);
    }

    private static CodeGraph serviceCalledByControllerExposedAsEndpoint() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(type("type:demo.Service", "demo.Service"));
        graph.upsertNode(new GraphNode("type:demo.Service#run()", EntityKind.METHOD, "run", Map.of(), SOURCE));
        graph.upsertNode(type("type:demo.Controller", "demo.Controller"));
        graph.upsertNode(new GraphNode("type:demo.Controller#handle()", EntityKind.METHOD, "handle", Map.of(), SOURCE));
        graph.upsertNode(new GraphNode("endpoint:GET:/orders", EntityKind.ENDPOINT, "GET /orders", Map.of(), SOURCE));
        graph.addEdge(new GraphEdge("type:demo.Service", "type:demo.Service#run()", RelationKind.DECLARES, Map.of(), SOURCE));
        graph.addEdge(new GraphEdge("type:demo.Controller", "type:demo.Controller#handle()", RelationKind.DECLARES, Map.of(), SOURCE));
        graph.addEdge(new GraphEdge("type:demo.Controller#handle()", "type:demo.Service#run()", RelationKind.CALLS, Map.of(), SOURCE));
        graph.addEdge(new GraphEdge("endpoint:GET:/orders", "type:demo.Controller#handle()", RelationKind.EXPOSES, Map.of(), SOURCE));
        return graph;
    }

    private static GraphNode type(String id, String name) {
        return new GraphNode(id, EntityKind.TYPE, name, Map.of(), SOURCE);
    }
}

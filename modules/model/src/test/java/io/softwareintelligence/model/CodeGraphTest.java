package io.softwareintelligence.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeGraphTest {
    private static final Provenance AT_LINE_10 = Provenance.syntax("A.java", 10, 1);

    @Test void records_the_same_relationship_at_the_same_position_once() {
        CodeGraph graph = new CodeGraph();
        graph.addEdge(edge("a", "b", RelationKind.CALLS, AT_LINE_10));
        graph.addEdge(edge("a", "b", RelationKind.CALLS, AT_LINE_10));

        assertEquals(1, graph.edges().size());
    }

    @Test void keeps_two_call_sites_on_different_lines_as_separate_evidence() {
        CodeGraph graph = new CodeGraph();
        graph.addEdge(edge("a", "b", RelationKind.CALLS, AT_LINE_10));
        graph.addEdge(edge("a", "b", RelationKind.CALLS, Provenance.syntax("A.java", 11, 1)));

        assertEquals(2, graph.edges().size());
    }

    @Test void a_declaration_replaces_a_node_created_by_a_reference_to_it() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(node("m", EntityKind.METHOD, Provenance.syntax("Caller.java", 4, 1)));
        graph.upsertNode(node("m", EntityKind.METHOD, Provenance.syntax("Declaring.java", 9, 1)), true);

        assertEquals("Declaring.java", graph.node("m").orElseThrow().provenance().file());
    }

    @Test void a_reference_never_overwrites_an_existing_declaration() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(node("m", EntityKind.METHOD, Provenance.syntax("Declaring.java", 9, 1)), true);
        graph.upsertNode(node("m", EntityKind.METHOD, Provenance.syntax("Caller.java", 4, 1)));

        assertEquals("Declaring.java", graph.node("m").orElseThrow().provenance().file());
    }

    @Test void source_identity_upgrades_a_provisional_external_symbol() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(node("t", EntityKind.EXTERNAL_SYMBOL, AT_LINE_10));
        graph.upsertNode(node("t", EntityKind.SERVICE, AT_LINE_10));

        assertEquals(EntityKind.SERVICE, graph.node("t").orElseThrow().kind());
        assertEquals(1, graph.nodes().size());
    }

    @Test void adjacency_is_indexed_in_both_directions() {
        CodeGraph graph = new CodeGraph();
        graph.addEdge(edge("a", "b", RelationKind.CALLS, AT_LINE_10));

        assertEquals(List.of("b"), graph.outgoing("a").stream().map(GraphEdge::to).toList());
        assertEquals(List.of("a"), graph.incoming("b").stream().map(GraphEdge::from).toList());
        assertTrue(graph.outgoing("b").isEmpty());
    }

    private static GraphEdge edge(String from, String to, RelationKind kind, Provenance provenance) {
        return new GraphEdge(from, to, kind, Map.of(), provenance);
    }

    private static GraphNode node(String id, EntityKind kind, Provenance provenance) {
        return new GraphNode(id, kind, id, Map.of(), provenance);
    }
}

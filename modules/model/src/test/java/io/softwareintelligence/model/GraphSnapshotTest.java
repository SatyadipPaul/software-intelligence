package io.softwareintelligence.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphSnapshotTest {
    private static final Provenance SOURCE = new Provenance("JDT_BINDING", 1.0, "src/main/java/demo/A.java", 4, 2);

    @Test void a_snapshot_round_trips_through_disk(@TempDir Path directory) throws IOException {
        CodeGraph graph = twoTypes();
        Path file = directory.resolve("snapshot.json");

        GraphSnapshot.write(graph, file);
        CodeGraph restored = GraphSnapshot.read(file);

        assertEquals(graph.nodes().size(), restored.nodes().size());
        assertEquals(graph.edges().size(), restored.edges().size());
        assertEquals(GraphJsonWriter.write(graph), GraphJsonWriter.write(restored));
    }

    @Test void attributes_and_awkward_characters_survive_the_round_trip(@TempDir Path directory) throws IOException {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(new GraphNode("type:demo.A", EntityKind.TYPE, "demo.A",
                Map.of("annotation.Query.value", "SELECT \"x\"\tFROM owners\nWHERE id = ?"), SOURCE));
        Path file = directory.resolve("snapshot.json");

        GraphSnapshot.write(graph, file);

        assertEquals("SELECT \"x\"\tFROM owners\nWHERE id = ?",
                GraphSnapshot.read(file).node("type:demo.A").orElseThrow().attributes().get("annotation.Query.value"));
    }

    @Test void a_snapshot_from_another_schema_is_refused(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("old.json");
        Files.writeString(file, "{\n  \"version\": \"0.1\",\n  \"nodes\": [],\n  \"edges\": []\n}\n");

        IOException failure = assertThrows(IOException.class, () -> GraphSnapshot.read(file));
        assertTrue(failure.getMessage().contains("schema 0.1"), failure.getMessage());
    }

    @Test void a_file_that_is_not_a_graph_says_so_rather_than_blaming_the_schema(@TempDir Path directory) throws IOException {
        Path file = Files.writeString(directory.resolve("notes.json"), "not json at all");

        IOException failure = assertThrows(IOException.class, () -> GraphSnapshot.read(file));

        assertTrue(failure.getMessage().contains("not a repository graph"), failure.getMessage());
        assertFalse(failure.getMessage().contains("uses schema  but"), "an empty version is not a version mismatch");
    }

    @Test void a_truncated_graph_reports_truncation_rather_than_a_parser_crash(@TempDir Path directory) throws IOException {
        Path file = Files.writeString(directory.resolve("half.json"),
                "{ \"version\": \"" + GraphJsonWriter.SCHEMA_VERSION + "\", \"nodes\": [ {\"id\":");

        IOException failure = assertThrows(IOException.class, () -> GraphSnapshot.read(file));

        assertTrue(failure.getMessage().contains("truncated"), failure.getMessage());
    }

    @Test void a_diff_reports_added_removed_and_re_resolved_relationships() {
        CodeGraph before = twoTypes();
        CodeGraph after = twoTypes();
        after.upsertNode(new GraphNode("type:demo.C", EntityKind.TYPE, "demo.C", Map.of(), SOURCE));
        after.addEdge(new GraphEdge("type:demo.A", "type:demo.C", RelationKind.DEPENDS_ON, Map.of(), SOURCE));

        GraphSnapshot.Diff diff = GraphSnapshot.diff(before, after);

        assertEquals(List.of("type:demo.C"), diff.addedNodes().stream().map(GraphNode::id).toList());
        assertEquals(1, diff.addedEdges().size());
        assertTrue(diff.removedNodes().isEmpty());
        assertTrue(GraphSnapshot.render(diff).contains("+1/-0 nodes"));
    }

    @Test void a_relationship_proven_by_a_better_resolver_is_reported_as_re_resolved() {
        CodeGraph before = new CodeGraph();
        before.addEdge(new GraphEdge("a", "b", RelationKind.CALLS, Map.of(),
                new Provenance("DISPATCH_NORMALIZED", 0.6, "A.java", 3, 1)));
        CodeGraph after = new CodeGraph();
        after.addEdge(new GraphEdge("a", "b", RelationKind.CALLS, Map.of(),
                new Provenance("JDT_BINDING", 1.0, "A.java", 3, 1)));

        GraphSnapshot.Diff diff = GraphSnapshot.diff(before, after);

        assertEquals(1, diff.reresolved().size());
        assertEquals("JDT_BINDING", diff.reresolved().get(0).after().provenance().resolver());
        assertTrue(diff.addedEdges().isEmpty());
    }

    @Test void an_identical_graph_produces_an_empty_diff() {
        assertTrue(GraphSnapshot.diff(twoTypes(), twoTypes()).isEmpty());
    }

    @Test void changed_symbols_name_the_types_a_what_if_should_assess() {
        CodeGraph before = twoTypes();
        CodeGraph after = twoTypes();
        after.upsertNode(new GraphNode("type:demo.A#extra()", EntityKind.METHOD, "extra", Map.of(), SOURCE));
        after.addEdge(new GraphEdge("type:demo.A", "type:demo.A#extra()", RelationKind.DECLARES, Map.of(), SOURCE));

        List<String> changed = GraphSnapshot.changedSymbols(GraphSnapshot.diff(before, after));

        assertEquals(List.of("type:demo.A"), changed);
        assertFalse(changed.contains("type:demo.B"));
    }

    private static CodeGraph twoTypes() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(new GraphNode("type:demo.A", EntityKind.SERVICE, "demo.A", Map.of("annotations", "Service"), SOURCE));
        graph.upsertNode(new GraphNode("type:demo.B", EntityKind.TYPE, "demo.B", Map.of(), SOURCE));
        graph.addEdge(new GraphEdge("type:demo.A", "type:demo.B", RelationKind.DEPENDS_ON, Map.of("resolution", "JDT_BINDING"), SOURCE));
        return graph;
    }
}

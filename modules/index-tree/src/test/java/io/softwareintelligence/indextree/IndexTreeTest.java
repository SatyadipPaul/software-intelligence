package io.softwareintelligence.indextree;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.Provenance;
import io.softwareintelligence.model.RelationKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexTreeTest {
    private static final Provenance SOURCE = new Provenance("JDT_AST", 1.0, "src/main/java/demo/Demo.java", 5, 1);

    @Test void the_tree_hangs_capabilities_and_modules_off_one_root() {
        IndexTree tree = IndexTreeBuilder.derive(commerceGraph());

        List<String> children = tree.children(tree.root()).stream().map(IndexNode::id).toList();

        assertTrue(children.contains("index:capability:payments"), children.toString());
        assertTrue(children.contains("index:module:demo"), children.toString());
        assertEquals(IndexKind.ROOT, tree.root().kind());
    }

    @Test void every_node_points_at_graph_nodes_and_invents_none() {
        CodeGraph graph = commerceGraph();

        IndexTree tree = IndexTreeBuilder.derive(graph);

        for (IndexNode node : tree.nodes()) {
            if (node.kind() == IndexKind.ROOT || node.kind() == IndexKind.GROUP || node.kind() == IndexKind.PACKAGE) continue;
            assertFalse(node.graphIds().isEmpty(), node.id() + " covers no graph node");
            for (String graphId : node.graphIds()) {
                assertTrue(graph.node(graphId).isPresent(), node.id() + " points at " + graphId + ", which is not in the graph");
            }
        }
    }

    @Test void tree_ids_can_never_collide_with_graph_ids() {
        CodeGraph graph = commerceGraph();

        for (IndexNode node : IndexTreeBuilder.derive(graph).nodes()) {
            assertTrue(node.id().startsWith("index:"), node.id());
            assertTrue(graph.node(node.id()).isEmpty(), node.id() + " collides with a graph id");
        }
    }

    @Test void two_derivations_of_the_same_graph_are_byte_identical() {
        CodeGraph graph = commerceGraph();

        assertEquals(IndexTreeJson.write(IndexTreeBuilder.derive(graph)),
                IndexTreeJson.write(IndexTreeBuilder.derive(graph)));
    }

    @Test void a_type_reaches_its_members_through_the_type_level() {
        IndexTree tree = IndexTreeBuilder.derive(commerceGraph());

        IndexNode type = tree.node("index:type:demo.PaymentService").orElseThrow();

        assertEquals(IndexKind.TYPE, type.kind());
        assertEquals(List.of("index:type:demo.PaymentService#authorize()"),
                type.children().stream().filter(id -> id.contains("authorize")).toList());
    }

    @Test void a_repository_with_no_framework_still_gets_a_structural_axis() {
        CodeGraph graph = new CodeGraph();
        declareType(graph, "type:lib.Parser", EntityKind.TYPE, "lib");
        declareType(graph, "type:lib.Lexer", EntityKind.TYPE, "lib");

        IndexTree tree = IndexTreeBuilder.derive(graph);

        assertTrue(tree.nodes().stream().noneMatch(node -> node.kind() == IndexKind.CAPABILITY));
        assertTrue(tree.nodes().stream().anyMatch(node -> node.id().equals("index:type:lib.Parser")),
                "a library type must still be reachable: " + tree.nodes().stream().map(IndexNode::id).toList());
    }

    @Test void a_type_the_architecture_layer_never_placed_is_still_reachable() {
        CodeGraph graph = new CodeGraph();
        declareType(graph, "type:demo.Orphan", EntityKind.TYPE, "demo");
        graph.upsertNode(new GraphNode("module:demo", EntityKind.MODULE, "demo", Map.of(), SOURCE), true);

        IndexTree tree = IndexTreeBuilder.derive(graph);

        assertTrue(tree.nodes().stream().anyMatch(node -> node.id().equals("index:type:demo.Orphan")),
                "an unplaced type must not fall out of the tree");
    }

    @Test void an_oversized_sibling_set_is_grouped_rather_than_truncated() {
        CodeGraph graph = new CodeGraph();
        for (int i = 0; i < 40; i++) declareType(graph, String.format("type:demo.Type%02d", i), EntityKind.TYPE, "demo");

        IndexTree tree = IndexTreeBuilder.derive(graph, new IndexTreeBuilder.Options(8, 8, true));

        List<String> reachable = new ArrayList<>();
        collect(tree, tree.root(), reachable);
        for (int i = 0; i < 40; i++) {
            assertTrue(reachable.contains(String.format("index:type:demo.Type%02d", i)), "type " + i + " was dropped");
        }
        for (IndexNode node : tree.nodes()) {
            assertTrue(node.children().size() <= 8, node.id() + " presents " + node.children().size() + " choices");
        }
        assertTrue(tree.nodes().stream().anyMatch(node -> node.kind() == IndexKind.GROUP));
    }

    @Test void a_summary_claim_reaches_the_card_and_nothing_else_does() {
        CodeGraph graph = commerceGraph();
        GraphNode service = graph.node("type:demo.PaymentService").orElseThrow();
        graph.upsertNode(new GraphNode(service.id(), service.kind(), service.name(),
                Map.of("claim.summary", "Authorizes card payments", "claim.confidence", "0.8"), service.provenance()), true);

        IndexTree tree = IndexTreeBuilder.derive(graph);

        assertEquals("Authorizes card payments", tree.node("index:type:demo.PaymentService").orElseThrow().facts().get("summary"));
    }

    @Test void a_tree_round_trips_through_its_file_form(@TempDir Path directory) throws IOException {
        IndexTree tree = IndexTreeBuilder.derive(commerceGraph());
        Path file = directory.resolve("tree.json");

        IndexTreeJson.write(tree, file);
        IndexTree read = IndexTreeJson.read(file);

        assertEquals(IndexTreeJson.write(tree), IndexTreeJson.write(read));
        assertEquals(tree.fingerprint(), read.fingerprint());
    }

    @Test void a_tree_built_from_a_different_graph_is_refused() {
        CodeGraph graph = commerceGraph();
        IndexTree tree = IndexTreeBuilder.derive(graph);
        CodeGraph moved = commerceGraph();
        declareType(moved, "type:demo.LateArrival", EntityKind.TYPE, "demo");

        assertNotEquals(GraphFingerprint.of(graph), GraphFingerprint.of(moved));
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> IndexTreeJson.requireCurrent(tree, moved));
        assertTrue(refused.getMessage().contains("rebuild"), refused.getMessage());
    }

    @Test void a_file_that_is_not_a_tree_is_reported_as_such(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("not-a-tree.json");
        Files.writeString(file, "{\"nodes\": []}");

        IOException failure = assertThrows(IOException.class, () -> IndexTreeJson.read(file));

        assertTrue(failure.getMessage().contains("not an index tree"), failure.getMessage());
    }

    @Test void a_card_lists_the_ids_a_navigator_may_choose() {
        IndexTree tree = IndexTreeBuilder.derive(commerceGraph());

        String card = IndexCards.render(tree, tree.root());

        assertTrue(card.contains("index:capability:payments"), card);
        assertTrue(card.contains("children ("), card);
    }

    @Test void a_single_module_of_one_flat_package_still_navigates() {
        // The degenerate shape: one source root, one package, no framework. Both axes collapse -
        // no capabilities, and a package level that would be a chain of one - so the tree is root ->
        // types -> members and a descent has only the type names to steer by.
        CodeGraph graph = new CodeGraph();
        List<String> types = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String id = String.format("type:flat.Widget%02d", i);
            declareType(graph, id, EntityKind.TYPE, "flat");
            declareMethod(graph, id, id + "#run()", "run");
            types.add(id);
        }
        graph.upsertNode(new GraphNode("module:flat", EntityKind.MODULE, "flat", Map.of(), SOURCE), true);
        for (String type : types) {
            graph.addEdge(new GraphEdge("module:flat", type, RelationKind.CONTAINS, Map.of(), SOURCE));
        }

        IndexTree tree = IndexTreeBuilder.derive(graph);

        assertTrue(tree.nodes().stream().noneMatch(node -> node.kind() == IndexKind.CAPABILITY),
                "no framework means no capability axis");
        assertTrue(tree.nodes().stream().noneMatch(node -> node.kind() == IndexKind.PACKAGE),
                "one package under one module is a chain of one, and must be collapsed away");

        List<String> reachable = new ArrayList<>();
        collect(tree, tree.root(), reachable);
        for (String type : types) {
            assertTrue(reachable.contains("index:" + type), type + " is unreachable from the root");
        }
        for (IndexNode node : tree.nodes()) {
            assertTrue(node.children().size() <= 24, node.id() + " presents " + node.children().size() + " choices");
        }
    }

    @Test void a_repository_with_no_module_nodes_at_all_still_has_a_root_and_its_types() {
        // --no-architecture removes the module layer entirely. Everything then hangs off one
        // synthetic module named for the repository rather than falling out of the tree.
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(new GraphNode("repo:flat", EntityKind.REPOSITORY, "flat", Map.of(), SOURCE), true);
        declareType(graph, "type:flat.Alpha", EntityKind.TYPE, "flat");
        declareType(graph, "type:flat.Beta", EntityKind.TYPE, "flat");

        IndexTree tree = IndexTreeBuilder.derive(graph);

        List<String> reachable = new ArrayList<>();
        collect(tree, tree.root(), reachable);
        assertTrue(reachable.contains("index:type:flat.Alpha"), reachable.toString());
        assertTrue(reachable.contains("index:type:flat.Beta"), reachable.toString());
        assertEquals("flat", tree.root().name());
    }

    private static void collect(IndexTree tree, IndexNode node, List<String> into) {
        into.add(node.id());
        for (IndexNode child : tree.children(node)) collect(tree, child, into);
    }

    /** A checkout slice with both axes: one capability over an endpoint, and one module of types. */
    private static CodeGraph commerceGraph() {
        CodeGraph graph = new CodeGraph();
        declareType(graph, "type:demo.PaymentService", EntityKind.SERVICE, "demo");
        declareType(graph, "type:demo.PaymentController", EntityKind.CONTROLLER, "demo");
        declareMethod(graph, "type:demo.PaymentService", "type:demo.PaymentService#authorize()", "authorize");

        graph.upsertNode(new GraphNode("module:demo", EntityKind.MODULE, "demo", Map.of("types", "2"), SOURCE), true);
        graph.addEdge(new GraphEdge("module:demo", "type:demo.PaymentService", RelationKind.CONTAINS, Map.of(), SOURCE));
        graph.addEdge(new GraphEdge("module:demo", "type:demo.PaymentController", RelationKind.CONTAINS, Map.of(), SOURCE));

        graph.upsertNode(new GraphNode("endpoint:POST:/payments/authorize", EntityKind.ENDPOINT, "POST /payments/authorize",
                Map.of("path", "/payments/authorize", "verb", "POST"), SOURCE), true);
        graph.upsertNode(new GraphNode("capability:payments", EntityKind.BUSINESS_CAPABILITY, "payments",
                Map.of("entryPoints", "1"), SOURCE), true);
        graph.addEdge(new GraphEdge("capability:payments", "endpoint:POST:/payments/authorize",
                RelationKind.PARTICIPATES_IN, Map.of("role", "entry-point"), SOURCE));
        return graph;
    }

    private static void declareType(CodeGraph graph, String id, EntityKind kind, String packageName) {
        String file = "src/main/java/" + packageName.replace('.', '/') + "/" + simpleName(id) + ".java";
        Provenance provenance = new Provenance("JDT_AST", 1.0, file, 5, 1);
        graph.upsertNode(new GraphNode("file:" + file, EntityKind.FILE, file, Map.of(), provenance), true);
        graph.upsertNode(new GraphNode("package:" + packageName, EntityKind.PACKAGE, packageName, Map.of(), provenance), true);
        graph.upsertNode(new GraphNode(id, kind, id.substring("type:".length()), Map.of(), provenance), true);
        graph.addEdge(new GraphEdge("file:" + file, "package:" + packageName, RelationKind.DECLARES, Map.of(), provenance));
        graph.addEdge(new GraphEdge("file:" + file, id, RelationKind.DECLARES, Map.of(), provenance));
    }

    private static void declareMethod(CodeGraph graph, String owner, String id, String name) {
        graph.upsertNode(new GraphNode(id, EntityKind.METHOD, name, Map.of(), SOURCE), true);
        graph.addEdge(new GraphEdge(owner, id, RelationKind.DECLARES, Map.of(), SOURCE));
    }

    private static String simpleName(String typeId) {
        String qualified = typeId.substring("type:".length());
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }
}

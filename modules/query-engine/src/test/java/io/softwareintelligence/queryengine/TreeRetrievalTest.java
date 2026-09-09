package io.softwareintelligence.queryengine;

import io.softwareintelligence.indextree.IndexTree;
import io.softwareintelligence.indextree.IndexTreeBuilder;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.ContextPacket;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeRetrievalTest {
    private static final Provenance SOURCE = new Provenance("JDT_AST", 1.0, "src/main/java/demo/Demo.java", 5, 1);

    @Test void descent_reaches_the_symbol_the_question_names() {
        CodeGraph graph = commerceGraph();
        IndexTree tree = IndexTreeBuilder.derive(graph);

        TreeNavigator.Descent descent = TreeNavigator.descend(tree, "what breaks if PaymentService changes?",
                QueryPlanner.classify("what breaks if PaymentService changes?"), 4, 5);

        assertTrue(descent.anchorGraphIds().contains("type:demo.PaymentService"), descent.anchorGraphIds().toString());
    }

    @Test void a_descent_records_the_step_where_each_branch_won() {
        IndexTree tree = IndexTreeBuilder.derive(commerceGraph());

        TreeNavigator.Descent descent = TreeNavigator.descend(tree, "which endpoints authorize payments?",
                QueryPlanner.classify("which endpoints authorize payments?"), 4, 5);

        assertFalse(descent.trace().isEmpty(), "a descent with no trace cannot be debugged");
        assertEquals(IndexTree.ROOT_ID, descent.trace().get(0).from());
        assertTrue(descent.trace().get(0).considered().stream().allMatch(scored -> !scored.reason().isBlank()));
    }

    @Test void an_endpoint_question_lands_on_the_operational_surface() {
        CodeGraph graph = commerceGraph();
        IndexTree tree = IndexTreeBuilder.derive(graph);

        TreeNavigator.Descent descent = TreeNavigator.descend(tree, "which REST endpoints does this expose?",
                QueryPlanner.classify("which REST endpoints does this expose?"), 4, 5);

        assertTrue(descent.anchorGraphIds().stream().anyMatch(id -> id.startsWith("endpoint:")),
                descent.anchorGraphIds().toString());
    }

    @Test void descent_is_identical_across_runs() {
        IndexTree tree = IndexTreeBuilder.derive(commerceGraph());
        String question = "what does PaymentService depend on?";

        assertEquals(TreeNavigator.descend(tree, question, QueryPlanner.classify(question), 3, 5).anchorGraphIds(),
                TreeNavigator.descend(tree, question, QueryPlanner.classify(question), 3, 5).anchorGraphIds());
    }

    @Test void tree_retrieval_returns_more_than_one_anchor_where_the_answer_is_plural() {
        CodeGraph graph = commerceGraph();
        IndexTree tree = IndexTreeBuilder.derive(graph);

        QueryPlanner.Answerable answerable = QueryPlanner.plan(graph, Bm25Index.over(graph), tree,
                "which endpoints exist in the payments capability?", 10, RetrievalMode.HYBRID, 5, 4);

        assertTrue(answerable.anchors().size() > 1, answerable.anchors().stream().map(GraphNode::id).toList().toString());
        assertEquals(answerable.anchors().size(), answerable.contexts().size());
    }

    @Test void the_flat_path_is_unchanged_by_the_tree_existing() {
        CodeGraph graph = commerceGraph();

        QueryPlanner.Answerable legacy = QueryPlanner.plan(graph, Bm25Index.over(graph),
                "what breaks if PaymentService changes?", 5);

        assertEquals(RetrievalMode.BM25, legacy.retrieval());
        assertEquals("type:demo.PaymentService", legacy.subject().orElseThrow().id());
        assertEquals(1, legacy.anchors().size());
        assertTrue(legacy.descent().isEmpty());
    }

    @Test void tree_mode_without_a_tree_says_what_to_build() {
        CodeGraph graph = commerceGraph();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> QueryPlanner.plan(graph, Bm25Index.over(graph), null, "anything", 5, RetrievalMode.TREE, 3, 4));

        assertTrue(failure.getMessage().contains("repo-intel index"), failure.getMessage());
    }

    @Test void a_merged_packet_keeps_every_anchors_evidence() {
        CodeGraph graph = commerceGraph();
        IndexTree tree = IndexTreeBuilder.derive(graph);

        QueryPlanner.Answerable answerable = QueryPlanner.plan(graph, Bm25Index.over(graph), tree,
                "which endpoints exist in the payments capability?", 10, RetrievalMode.HYBRID, 5, 4);
        ContextPacket merged = answerable.merged().orElseThrow();

        for (ContextPacket packet : answerable.contexts()) {
            for (GraphEdge edge : packet.evidence()) {
                assertTrue(merged.evidence().stream().anyMatch(candidate -> candidate.from().equals(edge.from())
                                && candidate.to().equals(edge.to()) && candidate.kind() == edge.kind()),
                        "merging dropped " + edge.from() + " -> " + edge.to());
            }
        }
    }

    @Test void compression_keeps_every_anchor_rather_than_only_the_first() {
        CodeGraph graph = commerceGraph();
        IndexTree tree = IndexTreeBuilder.derive(graph);
        QueryPlanner.Answerable answerable = QueryPlanner.plan(graph, Bm25Index.over(graph), tree,
                "which endpoints exist in the payments capability?", 10, RetrievalMode.HYBRID, 5, 4);
        ContextPacket merged = answerable.merged().orElseThrow();
        Set<String> anchors = answerable.anchorIds();

        ContextPacket compressed = QueryPlanner.compress(merged, anchors, QueryPlanner.estimateTokens(merged) / 2);

        assertTrue(QueryPlanner.estimateTokens(compressed) <= QueryPlanner.estimateTokens(merged));
        long touching = compressed.evidence().stream()
                .filter(edge -> anchors.contains(edge.from()) || anchors.contains(edge.to())).count();
        assertTrue(touching > 0, "compression dropped every anchor's own evidence");
    }

    @Test void claims_are_anchored_on_the_anchor_their_edge_touches() {
        CodeGraph graph = commerceGraph();
        IndexTree tree = IndexTreeBuilder.derive(graph);
        QueryPlanner.Answerable answerable = QueryPlanner.plan(graph, Bm25Index.over(graph), tree,
                "which endpoints exist in the payments capability?", 10, RetrievalMode.HYBRID, 5, 4);
        ContextPacket merged = answerable.merged().orElseThrow();

        List<VerifiedAnswer.Claim> claims = VerifiedAnswer.claimsFrom(merged, answerable.anchorIds());
        VerifiedAnswer.Answer answer = VerifiedAnswer.verify(graph, "which endpoints?", claims);

        assertTrue(answer.withheld().isEmpty(),
                answer.withheld().stream().map(VerifiedAnswer.VerifiedClaim::explanation).toList().toString());
    }

    // ------------------------------------------------------------ assistant-driven descent

    @Test void an_assistant_may_only_choose_ids_it_was_shown() {
        IndexTree tree = IndexTreeBuilder.derive(commerceGraph());
        NavigationSession.State state = NavigationSession.start(tree, "where are payments authorized?");

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> NavigationSession.advance(tree, state, List.of("index:type:demo.PaymentService")));

        assertTrue(rejected.getMessage().contains("not on the cards"), rejected.getMessage());
    }

    @Test void an_assistant_descent_reaches_an_anchor_through_presented_ids_only() {
        IndexTree tree = IndexTreeBuilder.derive(commerceGraph());
        NavigationSession.State state = NavigationSession.start(tree, "where are payments authorized?");

        // The descent an assistant would drive: down the structural axis, one presented id per step.
        for (String choice : List.of("index:module:demo", "index:type:demo.PaymentService")) {
            assertTrue(NavigationSession.presented(tree, state).contains(choice),
                    choice + " was not offered at step " + state.step() + ": " + NavigationSession.presented(tree, state));
            state = NavigationSession.advance(tree, state, List.of(choice));
        }
        state = NavigationSession.advance(tree, state, List.of("index:type:demo.PaymentService"));

        assertTrue(state.complete(), "choosing an open card stops the descent there: " + state.frontier());
        assertTrue(NavigationSession.anchorGraphIds(tree, state).contains("type:demo.PaymentService"),
                state.anchors().toString());
    }

    @Test void a_packet_carries_the_rules_and_the_ids_that_may_be_answered() {
        IndexTree tree = IndexTreeBuilder.derive(commerceGraph());

        String packet = NavigationSession.packet(tree, NavigationSession.start(tree, "where are payments authorized?"));

        assertTrue(packet.contains("Choose only ids that appear on the cards below"), packet);
        assertTrue(packet.contains("index:module:demo") || packet.contains("index:capability:payments"), packet);
        assertTrue(packet.contains("\"chosen\""), packet);
    }

    @Test void a_session_round_trips_and_refuses_a_different_graph(@TempDir Path directory) throws IOException {
        IndexTree tree = IndexTreeBuilder.derive(commerceGraph());
        NavigationSession.State state = NavigationSession.advance(tree,
                NavigationSession.start(tree, "where are payments authorized?"),
                List.of(NavigationSession.presented(tree, NavigationSession.start(tree, "q")).get(0)));
        Path file = directory.resolve("nav.json");

        NavigationSession.write(state, file);
        NavigationSession.State read = NavigationSession.read(file);

        assertEquals(state.frontier(), read.frontier());
        assertEquals(state.anchors(), read.anchors());
        assertEquals(state.step(), read.step());
        assertEquals(state.question(), read.question());

        CodeGraph other = commerceGraph();
        other.upsertNode(new GraphNode("type:demo.Latecomer", EntityKind.TYPE, "demo.Latecomer", Map.of(), SOURCE), true);
        IndexTree otherTree = IndexTreeBuilder.derive(other);
        assertThrows(IllegalStateException.class, () -> NavigationSession.requireCurrent(read, otherTree));
    }

    @Test void choices_are_read_from_the_document_an_assistant_answers_with(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("choices.json");
        Files.writeString(file, "{\"chosen\": [\"index:module:demo\", \"index:capability:payments\"]}");

        assertEquals(List.of("index:module:demo", "index:capability:payments"), NavigationSession.readChoices(file));
    }

    /** The same checkout slice the index-tree tests use: one capability, one module, one service. */
    private static CodeGraph commerceGraph() {
        CodeGraph graph = new CodeGraph();
        declareType(graph, "type:demo.PaymentService", EntityKind.SERVICE, "demo");
        declareType(graph, "type:demo.PaymentController", EntityKind.CONTROLLER, "demo");
        declareType(graph, "type:demo.OrderService", EntityKind.SERVICE, "demo");
        declareMethod(graph, "type:demo.PaymentService", "type:demo.PaymentService#authorize()", "authorize");
        declareMethod(graph, "type:demo.PaymentController", "type:demo.PaymentController#pay()", "pay");
        graph.addEdge(new GraphEdge("type:demo.PaymentController#pay()", "type:demo.PaymentService#authorize()",
                RelationKind.CALLS, Map.of(), SOURCE));

        graph.upsertNode(new GraphNode("module:demo", EntityKind.MODULE, "demo", Map.of("types", "3"), SOURCE), true);
        for (String type : List.of("type:demo.PaymentService", "type:demo.PaymentController", "type:demo.OrderService")) {
            graph.addEdge(new GraphEdge("module:demo", type, RelationKind.CONTAINS, Map.of(), SOURCE));
        }

        graph.upsertNode(new GraphNode("endpoint:POST:/payments/authorize", EntityKind.ENDPOINT, "POST /payments/authorize",
                Map.of("path", "/payments/authorize", "verb", "POST"), SOURCE), true);
        graph.upsertNode(new GraphNode("endpoint:GET:/payments/status", EntityKind.ENDPOINT, "GET /payments/status",
                Map.of("path", "/payments/status", "verb", "GET"), SOURCE), true);
        graph.addEdge(new GraphEdge("type:demo.PaymentController#pay()", "endpoint:POST:/payments/authorize",
                RelationKind.EXPOSES, Map.of(), SOURCE));
        graph.upsertNode(new GraphNode("capability:payments", EntityKind.BUSINESS_CAPABILITY, "payments",
                Map.of("entryPoints", "2"), SOURCE), true);
        graph.addEdge(new GraphEdge("capability:payments", "endpoint:POST:/payments/authorize",
                RelationKind.PARTICIPATES_IN, Map.of("role", "entry-point"), SOURCE));
        graph.addEdge(new GraphEdge("capability:payments", "endpoint:GET:/payments/status",
                RelationKind.PARTICIPATES_IN, Map.of("role", "entry-point"), SOURCE));
        return graph;
    }

    private static void declareType(CodeGraph graph, String id, EntityKind kind, String packageName) {
        String qualified = id.substring("type:".length());
        String file = "src/main/java/" + packageName.replace('.', '/') + "/"
                + qualified.substring(qualified.lastIndexOf('.') + 1) + ".java";
        Provenance provenance = new Provenance("JDT_AST", 1.0, file, 5, 1);
        graph.upsertNode(new GraphNode("file:" + file, EntityKind.FILE, file, Map.of(), provenance), true);
        graph.upsertNode(new GraphNode("package:" + packageName, EntityKind.PACKAGE, packageName, Map.of(), provenance), true);
        graph.upsertNode(new GraphNode(id, kind, qualified, Map.of(), provenance), true);
        graph.addEdge(new GraphEdge("file:" + file, "package:" + packageName, RelationKind.DECLARES, Map.of(), provenance));
        graph.addEdge(new GraphEdge("file:" + file, id, RelationKind.DECLARES, Map.of(), provenance));
    }

    private static void declareMethod(CodeGraph graph, String owner, String id, String name) {
        graph.upsertNode(new GraphNode(id, EntityKind.METHOD, name, Map.of(), SOURCE), true);
        graph.addEdge(new GraphEdge(owner, id, RelationKind.DECLARES, Map.of(), SOURCE));
    }
}

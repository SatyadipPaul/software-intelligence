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

    @Test void choosing_a_heading_with_nothing_behind_it_says_so_rather_than_that_it_was_never_shown() {
        // The root is printed at the top of the first card, so "not on the cards" would be false,
        // and a reader told that would reasonably try the same id again.
        IndexTree tree = IndexTreeBuilder.derive(commerceGraph());
        NavigationSession.State state = NavigationSession.start(tree, "where are payments authorized?");
        String root = tree.root().id();

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> NavigationSession.advance(tree, state, List.of(root)));

        assertTrue(rejected.getMessage().contains("is a heading"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("choose one of its children"), rejected.getMessage());
        assertFalse(rejected.getMessage().contains("not on the cards"), rejected.getMessage());
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

    // ------------------------------------------------------------ defects the real corpora found

    @Test void a_question_word_is_never_mistaken_for_the_subject() {
        // "Which" is capitalized because a question starts with it, and it is longer than "Owner".
        // Petclinic scored 1.000 before this and 0.800 after tree navigation started steering by the
        // subject, because no branch holds a symbol called Which.
        assertEquals("Owner", QueryPlanner.subjectOf("Which table does the Owner entity persist to?"));
        assertEquals("Vet", QueryPlanner.subjectOf("What does the Vet entity map to?"));
        assertEquals("PaymentService", QueryPlanner.subjectOf("What breaks if PaymentService changes?"));
    }

    @Test void a_branch_is_chosen_for_what_it_holds_not_for_what_it_says() {
        // The shape that made jackson-databind score 0.400: a package whose name repeats the query's
        // words many times, against the one package that actually contains the symbol.
        CodeGraph graph = new CodeGraph();
        declareType(graph, "type:demo.JsonNode", EntityKind.TYPE, "demo");
        for (int i = 0; i < 12; i++) declareType(graph, "type:demo.node.JsonNodeHelper" + i, EntityKind.TYPE, "demo.node");
        graph.upsertNode(new GraphNode("module:demo", EntityKind.MODULE, "demo", Map.of(), SOURCE), true);
        graph.addEdge(new GraphEdge("module:demo", "type:demo.JsonNode", RelationKind.CONTAINS, Map.of(), SOURCE));
        graph.upsertNode(new GraphNode("module:demo.node", EntityKind.MODULE, "demo.node", Map.of(), SOURCE), true);
        for (int i = 0; i < 12; i++) {
            graph.addEdge(new GraphEdge("module:demo.node", "type:demo.node.JsonNodeHelper" + i, RelationKind.CONTAINS, Map.of(), SOURCE));
        }
        IndexTree tree = IndexTreeBuilder.derive(graph);
        String question = "What is affected by a change to JsonNode?";

        TreeNavigator.Descent descent = TreeNavigator.descend(tree, question, QueryPlanner.classify(question), 4, 3);

        assertEquals("type:demo.JsonNode", descent.anchorGraphIds().get(0),
                "the branch holding the subject must win over the one that merely repeats its words: "
                        + descent.anchorGraphIds());
    }

    @Test void a_branch_that_holds_the_subject_outranks_a_higher_scoring_one() {
        CodeGraph graph = commerceGraph();
        IndexTree tree = IndexTreeBuilder.derive(graph);
        String question = "what breaks if OrderService changes?";

        TreeNavigator.Descent descent = TreeNavigator.descend(tree, question, QueryPlanner.classify(question), 4, 5);

        TreeNavigator.Step first = descent.trace().get(0);
        TreeNavigator.Scored best = first.considered().get(0);
        assertTrue(best.holdsSubject(), "the branch holding OrderService must sort first: " + best.reason());
    }

    @Test void two_trees_navigated_alternately_are_both_cached_and_both_right() {
        // The one-entry cache this replaced would have rebuilt an index on every call here, and a
        // cache keyed on anything but identity would have answered one tree from the other's index.
        IndexTree first = IndexTreeBuilder.derive(commerceGraph());
        CodeGraph other = commerceGraph();
        declareType(other, "type:demo.Latecomer", EntityKind.TYPE, "demo");
        other.addEdge(new GraphEdge("module:demo", "type:demo.Latecomer", RelationKind.CONTAINS, Map.of(), SOURCE));
        IndexTree second = IndexTreeBuilder.derive(other);
        String question = "what breaks if Latecomer changes?";

        for (int round = 0; round < 3; round++) {
            assertFalse(TreeNavigator.descend(first, question, QueryPlanner.classify(question), 4, 5)
                            .anchorGraphIds().contains("type:demo.Latecomer"),
                    "the first tree does not hold Latecomer and must not answer as though it did");
            assertTrue(TreeNavigator.descend(second, question, QueryPlanner.classify(question), 4, 5)
                            .anchorGraphIds().contains("type:demo.Latecomer"),
                    "the second tree holds Latecomer and must find it every time");
        }
    }

    @Test void the_card_index_is_reused_across_questions_of_one_tree() {
        IndexTree tree = IndexTreeBuilder.derive(commerceGraph());
        String question = "what breaks if PaymentService changes?";

        // Same tree, twice: the second descent must agree exactly with the first, which is what
        // makes caching the index safe.
        assertEquals(TreeNavigator.descend(tree, question, QueryPlanner.classify(question), 4, 5).anchorGraphIds(),
                TreeNavigator.descend(tree, question, QueryPlanner.classify(question), 4, 5).anchorGraphIds());

        IndexTree rebuilt = IndexTreeBuilder.derive(commerceGraph());
        assertEquals(TreeNavigator.descend(tree, question, QueryPlanner.classify(question), 4, 5).anchorGraphIds(),
                TreeNavigator.descend(rebuilt, question, QueryPlanner.classify(question), 4, 5).anchorGraphIds(),
                "a second tree instance must not be answered from the first tree's cached index");
    }

    @Test void a_method_named_like_the_subject_does_not_stand_in_for_the_type() {
        // junit5 has two types called Test and 408 methods called test(). Indexing both in one list
        // made "holds the subject" true almost everywhere and sent the descent to whichever module
        // said "test" most often, which is how it lost the question outright.
        CodeGraph graph = new CodeGraph();
        declareType(graph, "type:demo.api.Check", EntityKind.TYPE, "demo.api");
        graph.upsertNode(new GraphNode("module:demo.api", EntityKind.MODULE, "demo.api", Map.of(), SOURCE), true);
        graph.addEdge(new GraphEdge("module:demo.api", "type:demo.api.Check", RelationKind.CONTAINS, Map.of(), SOURCE));

        graph.upsertNode(new GraphNode("module:demo.noise", EntityKind.MODULE, "demo.noise", Map.of(), SOURCE), true);
        for (int i = 0; i < 20; i++) {
            String owner = "type:demo.noise.Runner" + i;
            declareType(graph, owner, EntityKind.TYPE, "demo.noise");
            graph.addEdge(new GraphEdge("module:demo.noise", owner, RelationKind.CONTAINS, Map.of(), SOURCE));
            declareMethod(graph, owner, owner + "#check()", "check");
        }
        IndexTree tree = IndexTreeBuilder.derive(graph);
        String question = "Which module contains the Check type?";

        TreeNavigator.Descent descent = TreeNavigator.descend(tree, question, QueryPlanner.classify(question), 4, 3);

        assertEquals("module:demo.api", descent.anchorGraphIds().get(0),
                "twenty methods called check() must not outvote the one type called Check: "
                        + descent.anchorGraphIds());
    }

    @Test void a_member_is_still_found_when_no_type_carries_the_name() {
        // The other half of the rule: falling back to members is what keeps a question about
        // PaymentService.authorize able to reach the method.
        IndexTree tree = IndexTreeBuilder.derive(commerceGraph());
        String question = "what calls PaymentService.authorize?";

        TreeNavigator.Descent descent = TreeNavigator.descend(tree, question, QueryPlanner.classify(question), 4, 5);

        assertTrue(descent.anchorGraphIds().stream().anyMatch(id -> id.contains("authorize")),
                descent.anchorGraphIds().toString());
    }

    @Test void a_flat_single_package_repository_still_retrieves_its_symbols() {
        // The shape with the least for a descent to steer by: no capabilities, no package level,
        // one module of thirty look-alike types. Tree retrieval has to hold up here or it is only
        // an advantage on repositories that were already well organised.
        CodeGraph graph = new CodeGraph();
        List<String> types = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String id = String.format("type:flat.Widget%02d", i);
            declareType(graph, id, EntityKind.TYPE, "flat");
            types.add(id);
        }
        graph.upsertNode(new GraphNode("module:flat", EntityKind.MODULE, "flat", Map.of(), SOURCE), true);
        for (String type : types) graph.addEdge(new GraphEdge("module:flat", type, RelationKind.CONTAINS, Map.of(), SOURCE));
        IndexTree tree = IndexTreeBuilder.derive(graph);

        for (String target : List.of("Widget07", "Widget23")) {
            String question = "what breaks if " + target + " changes?";
            TreeNavigator.Descent descent = TreeNavigator.descend(tree, question, QueryPlanner.classify(question), 4, 5);
            assertEquals("type:flat." + target, descent.anchorGraphIds().get(0),
                    "a flat repository still has to route through its groups to the right type: "
                            + descent.anchorGraphIds());
        }
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

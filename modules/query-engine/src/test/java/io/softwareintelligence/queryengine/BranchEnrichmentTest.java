package io.softwareintelligence.queryengine;

import io.softwareintelligence.indextree.IndexTree;
import io.softwareintelligence.indextree.IndexTreeBuilder;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.Provenance;
import io.softwareintelligence.model.RelationKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchEnrichmentTest {
    private static final Provenance SOURCE = new Provenance("JDT_AST", 1.0, "src/main/java/demo/Demo.java", 5, 1);

    @Test void a_branch_that_says_nothing_and_holds_a_lot_ranks_first() {
        CodeGraph graph = twoModules();

        List<EnrichmentPlanner.Candidate> ranked = BranchEnrichment.rank(graph, IndexTreeBuilder.derive(graph));

        assertEquals("module:demo.util", ranked.get(0).id(), ranked.toString());
        assertTrue(ranked.get(0).reason().contains("name says nothing"), ranked.get(0).reason());
    }

    @Test void a_branch_with_one_child_offers_no_choice_to_steer() {
        CodeGraph graph = new CodeGraph();
        declareType(graph, "type:demo.solo.Only", EntityKind.TYPE, "demo.solo");
        module(graph, "module:demo.solo", "demo.solo", List.of("type:demo.solo.Only"));

        List<EnrichmentPlanner.Candidate> ranked = BranchEnrichment.rank(graph, IndexTreeBuilder.derive(graph));

        assertTrue(ranked.stream().noneMatch(candidate -> candidate.id().equals("module:demo.solo")), ranked.toString());
    }

    @Test void a_branch_that_already_carries_a_summary_is_not_offered_again() {
        CodeGraph graph = twoModules();
        GraphNode module = graph.node("module:demo.util").orElseThrow();
        graph.upsertNode(new GraphNode(module.id(), module.kind(), module.name(),
                Map.of("claim.summary", "Payment authorization helpers"), module.provenance()), true);

        List<EnrichmentPlanner.Candidate> ranked = BranchEnrichment.rank(graph, IndexTreeBuilder.derive(graph));

        assertTrue(ranked.stream().noneMatch(candidate -> candidate.id().equals("module:demo.util")), ranked.toString());
    }

    @Test void a_group_is_never_offered_because_no_claim_could_be_pinned_to_it() {
        CodeGraph graph = new CodeGraph();
        List<String> types = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String id = String.format("type:demo.wide.Type%02d", i);
            declareType(graph, id, EntityKind.TYPE, "demo.wide");
            types.add(id);
        }
        module(graph, "module:demo.wide", "demo.wide", types);
        IndexTree tree = IndexTreeBuilder.derive(graph, new IndexTreeBuilder.Options(8, 8, true));

        List<EnrichmentPlanner.Candidate> ranked = BranchEnrichment.rank(graph, tree);

        assertTrue(ranked.stream().noneMatch(candidate -> candidate.id().contains("#group")), ranked.toString());
        assertTrue(ranked.stream().anyMatch(candidate -> candidate.id().equals("module:demo.wide")), ranked.toString());
    }

    @Test void test_code_is_ranked_far_below_the_code_it_tests() {
        CodeGraph graph = twoModules();
        String testFile = "src/test/java/demo/util/AuthorizerTests.java";
        Provenance inTests = new Provenance("JDT_AST", 1.0, testFile, 9, 1);
        graph.upsertNode(new GraphNode("file:" + testFile, EntityKind.FILE, testFile, Map.of(), inTests), true);
        graph.upsertNode(new GraphNode("type:demo.util.AuthorizerTests", EntityKind.TYPE, "demo.util.AuthorizerTests",
                Map.of(), inTests), true);
        graph.addEdge(new GraphEdge("file:" + testFile, "type:demo.util.AuthorizerTests", RelationKind.DECLARES, Map.of(), inTests));
        for (int i = 0; i < 8; i++) {
            String method = "type:demo.util.AuthorizerTests#case" + i + "()";
            graph.upsertNode(new GraphNode(method, EntityKind.METHOD, "case" + i, Map.of(), inTests), true);
            graph.addEdge(new GraphEdge("type:demo.util.AuthorizerTests", method, RelationKind.DECLARES, Map.of(), inTests));
        }
        graph.addEdge(new GraphEdge("module:demo.util", "type:demo.util.AuthorizerTests", RelationKind.CONTAINS, Map.of(), inTests));

        List<EnrichmentPlanner.Candidate> ranked = BranchEnrichment.rank(graph, IndexTreeBuilder.derive(graph));

        EnrichmentPlanner.Candidate tests = ranked.stream()
                .filter(candidate -> candidate.id().equals("type:demo.util.AuthorizerTests")).findFirst().orElseThrow();
        assertTrue(tests.reason().contains("test code"), tests.reason());
        assertTrue(ranked.indexOf(tests) > 0, "test code must not outrank the branches questions route through");
    }

    @Test void the_budget_is_respected_and_the_audit_says_what_was_skipped() {
        CodeGraph graph = twoModules();
        EnrichmentPlanner.Budget budget = new EnrichmentPlanner.Budget(200, 0.003);

        EnrichmentPlanner.Plan plan = BranchEnrichment.plan(graph, IndexTreeBuilder.derive(graph), budget);

        assertTrue(plan.plannedTokens() <= 200, "planned " + plan.plannedTokens());
        assertFalse(plan.selected().isEmpty());
        assertTrue(EnrichmentPlanner.audit(plan, budget).contains("ENRICHMENT PLAN"));
    }

    @Test void a_summary_on_a_branch_steers_a_descent_that_its_name_would_lose() {
        // The payoff, end to end: the branch holding the answer is called "util", so its own words
        // say nothing about authorization. A pinned claim on it is the one thing that can help, and
        // the card is where a descent reads it.
        CodeGraph graph = twoModules();
        String question = "where is authorization handled?";

        List<String> withoutSummary = TreeNavigator.descend(IndexTreeBuilder.derive(graph), question,
                QueryPlanner.classify(question), 1, 3).anchorGraphIds();

        GraphNode module = graph.node("module:demo.util").orElseThrow();
        graph.upsertNode(new GraphNode(module.id(), module.kind(), module.name(),
                Map.of("claim.summary", "Authorization checks for payments"), module.provenance()), true);
        List<String> withSummary = TreeNavigator.descend(IndexTreeBuilder.derive(graph), question,
                QueryPlanner.classify(question), 1, 3).anchorGraphIds();

        assertFalse(withoutSummary.contains("module:demo.util"),
                "the branch is unreachable by its own vocabulary, which is what makes this the interesting case: " + withoutSummary);
        assertTrue(withSummary.contains("module:demo.util"),
                "a pinned summary must reach the card and steer the descent: " + withSummary);
    }

    /** Two modules: one named for its domain, one named {@code util} and holding more. */
    private static CodeGraph twoModules() {
        CodeGraph graph = new CodeGraph();
        List<String> utilTypes = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String id = String.format("type:demo.util.Widget%d", i);
            declareType(graph, id, EntityKind.TYPE, "demo.util");
            utilTypes.add(id);
        }
        module(graph, "module:demo.util", "demo.util", utilTypes);

        List<String> catalogTypes = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String id = String.format("type:demo.catalog.Product%d", i);
            declareType(graph, id, EntityKind.TYPE, "demo.catalog");
            catalogTypes.add(id);
        }
        module(graph, "module:demo.catalog", "demo.catalog", catalogTypes);
        return graph;
    }

    private static void module(CodeGraph graph, String id, String name, List<String> types) {
        graph.upsertNode(new GraphNode(id, EntityKind.MODULE, name, Map.of("types", Integer.toString(types.size())), SOURCE), true);
        for (String type : types) {
            graph.addEdge(new GraphEdge(id, type, RelationKind.CONTAINS, Map.of(), SOURCE));
        }
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
}

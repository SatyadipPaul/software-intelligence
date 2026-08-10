package io.softwareintelligence.architecture;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.GraphQueries;
import io.softwareintelligence.model.Provenance;
import io.softwareintelligence.model.RelationKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureTest {
    private static final Provenance SOURCE = new Provenance("JDT_AST", 1.0, "src/main/java/demo/Demo.java", 5, 1);

    @Test void connected_components_separate_unrelated_clusters() {
        CodeGraph graph = twoClusters();

        List<Communities.Community> communities = Communities.detect(graph, Communities.Strategy.CONNECTED_COMPONENTS, 2);

        assertEquals(2, communities.size());
        assertEquals(List.of("type:demo.A", "type:demo.B", "type:demo.C"), communities.get(0).members());
        assertEquals(List.of("type:demo.X", "type:demo.Y"), communities.get(1).members());
    }

    @Test void k_core_drops_peripheral_types() {
        CodeGraph graph = twoClusters();

        List<Communities.Community> core = Communities.detect(graph, Communities.Strategy.K_CORE, 2);

        assertTrue(core.stream().flatMap(community -> community.members().stream()).noneMatch(id -> id.equals("type:demo.X")),
                "a degree-1 type is not part of a 2-core");
    }

    @Test void clustering_is_stable_across_runs() {
        CodeGraph graph = twoClusters();

        assertEquals(Communities.detect(graph, Communities.Strategy.CONNECTED_COMPONENTS, 2),
                Communities.detect(graph, Communities.Strategy.CONNECTED_COMPONENTS, 2));
    }

    @Test void centrality_ranks_the_most_depended_upon_type_first() {
        CodeGraph graph = twoClusters();

        List<Centrality.Score> ranked = Centrality.rank(graph);

        assertEquals("type:demo.C", ranked.get(0).id(), ranked.toString());
        assertTrue(ranked.get(0).pageRank() > ranked.get(ranked.size() - 1).pageRank());
    }

    @Test void modules_are_detected_from_package_layout_with_coupling_counts() {
        CodeGraph graph = new CodeGraph();
        declareType(graph, "type:demo.web.Controller", EntityKind.CONTROLLER);
        declareType(graph, "type:demo.core.Service", EntityKind.SERVICE);
        graph.addEdge(edge("type:demo.web.Controller", "type:demo.core.Service", RelationKind.DEPENDS_ON));

        List<Modules.Subsystem> subsystems = Modules.detect(graph);

        assertEquals(List.of("demo.core", "demo.web"), subsystems.stream().map(Modules.Subsystem::name).toList());
        assertEquals(Map.of("demo.core", 1), subsystems.get(1).dependsOn());
        assertTrue(graph.node("module:demo.web").isPresent());
    }

    @Test void a_nested_type_does_not_become_its_own_module() {
        CodeGraph graph = new CodeGraph();
        String file = "file:src/main/java/demo/Outer.java";
        graph.upsertNode(new GraphNode(file, EntityKind.FILE, file, Map.of(), SOURCE));
        graph.upsertNode(new GraphNode("package:demo", EntityKind.PACKAGE, "demo", Map.of(), SOURCE));
        graph.addEdge(edge(file, "package:demo", RelationKind.DECLARES));
        graph.upsertNode(new GraphNode("type:demo.Outer", EntityKind.TYPE, "demo.Outer", Map.of(), SOURCE));
        graph.addEdge(edge(file, "type:demo.Outer", RelationKind.DECLARES));
        // A nested type is named pkg.Outer.Inner; trimming its last segment would invent a module.
        graph.upsertNode(new GraphNode("type:demo.Outer.Inner", EntityKind.TYPE, "demo.Outer.Inner", Map.of(), SOURCE));
        graph.addEdge(edge("type:demo.Outer", "type:demo.Outer.Inner", RelationKind.DECLARES));

        List<Modules.Subsystem> subsystems = Modules.detect(graph);

        assertEquals(List.of("demo"), subsystems.stream().map(Modules.Subsystem::name).toList());
        assertEquals(2, subsystems.get(0).types().size(), "both the outer and nested type belong to package demo");
    }

    @Test void a_workflow_runs_from_an_endpoint_to_the_table_it_touches() {
        CodeGraph graph = endpointToTable();

        List<Workflows.Workflow> workflows = Workflows.discover(graph, 6);

        assertEquals(1, workflows.size());
        Workflows.Workflow workflow = workflows.get(0);
        assertEquals("endpoint:GET:/orders", workflow.entryPoint());
        assertTrue(workflow.steps().contains("type:demo.Controller#list()"));
        assertTrue(workflow.touches().contains("table:orders"), workflow.toString());
        assertEquals(EntityKind.WORKFLOW, graph.node("workflow:endpoint:GET:/orders").orElseThrow().kind());
    }

    @Test void risk_is_explained_factor_by_factor_and_scaled_by_the_weakest_evidence() {
        CodeGraph graph = endpointToTable();
        GraphNode subject = graph.node("type:demo.Repository").orElseThrow();

        RiskScore.Assessment assessment = RiskScore.assess(graph, GraphQueries.impact(graph, subject, 4));

        assertTrue(assessment.score() > 0);
        assertTrue(assessment.factors().stream().anyMatch(factor -> factor.name().equals("exposed-endpoints") && factor.count() >= 1));
        assertTrue(assessment.factors().stream().anyMatch(factor -> factor.name().equals("evidence-confidence")));
        assertTrue(RiskScore.explain(assessment).contains("RISK"));
    }

    @Test void weaker_evidence_produces_a_lower_score_for_the_same_shape() {
        CodeGraph strong = endpointToTable(1.0);
        CodeGraph weak = endpointToTable(0.6);

        double strongScore = RiskScore.assess(strong, GraphQueries.impact(strong, strong.node("type:demo.Repository").orElseThrow(), 4)).score();
        double weakScore = RiskScore.assess(weak, GraphQueries.impact(weak, weak.node("type:demo.Repository").orElseThrow(), 4)).score();

        assertTrue(weakScore < strongScore,
                "an impact proven only by dispatch guesses must not score as high as one proven by bindings");
    }

    @Test void a_redundant_weaker_edge_does_not_lower_a_score_that_stronger_proof_supports() {
        CodeGraph graph = endpointToTable(1.0);
        double before = RiskScore.assess(graph, GraphQueries.impact(graph, graph.node("type:demo.Repository").orElseThrow(), 4)).score();
        graph.addEdge(new GraphEdge("type:demo.Controller#list()", "type:demo.Repository#findAll()", RelationKind.CALLS,
                Map.of(), new Provenance("DISPATCH_NORMALIZED", 0.6, "src/main/java/demo/Other.java", 9, 1)));

        double after = RiskScore.assess(graph, GraphQueries.impact(graph, graph.node("type:demo.Repository").orElseThrow(), 4)).score();

        assertEquals(before, after, 1e-9);
    }

    @Test void an_unreferenced_symbol_scores_no_risk() {
        CodeGraph graph = new CodeGraph();
        declareType(graph, "type:demo.Orphan", EntityKind.TYPE);

        RiskScore.Assessment assessment = RiskScore.assess(graph, GraphQueries.impact(graph, graph.node("type:demo.Orphan").orElseThrow(), 4));

        assertEquals("NONE", assessment.band());
        assertFalse(assessment.factors().isEmpty());
    }

    private static CodeGraph twoClusters() {
        CodeGraph graph = new CodeGraph();
        declareType(graph, "type:demo.A", EntityKind.TYPE);
        declareType(graph, "type:demo.B", EntityKind.TYPE);
        declareType(graph, "type:demo.C", EntityKind.TYPE);
        declareType(graph, "type:demo.X", EntityKind.TYPE);
        declareType(graph, "type:demo.Y", EntityKind.TYPE);
        graph.addEdge(edge("type:demo.A", "type:demo.C", RelationKind.DEPENDS_ON));
        graph.addEdge(edge("type:demo.B", "type:demo.C", RelationKind.DEPENDS_ON));
        graph.addEdge(edge("type:demo.A", "type:demo.B", RelationKind.DEPENDS_ON));
        graph.addEdge(edge("type:demo.X", "type:demo.Y", RelationKind.DEPENDS_ON));
        return graph;
    }

    private static CodeGraph endpointToTable() { return endpointToTable(1.0); }

    private static CodeGraph endpointToTable(double callConfidence) {
        CodeGraph graph = new CodeGraph();
        declareType(graph, "type:demo.Controller", EntityKind.CONTROLLER);
        declareType(graph, "type:demo.Repository", EntityKind.REPOSITORY_COMPONENT);
        graph.upsertNode(new GraphNode("type:demo.Controller#list()", EntityKind.METHOD, "list", Map.of(), SOURCE));
        graph.upsertNode(new GraphNode("type:demo.Repository#findAll()", EntityKind.METHOD, "findAll", Map.of(), SOURCE));
        graph.upsertNode(new GraphNode("endpoint:GET:/orders", EntityKind.ENDPOINT, "GET /orders", Map.of(), SOURCE));
        graph.upsertNode(new GraphNode("table:orders", EntityKind.DATABASE_TABLE, "orders", Map.of(), SOURCE));
        graph.addEdge(edge("type:demo.Controller", "type:demo.Controller#list()", RelationKind.DECLARES));
        graph.addEdge(edge("type:demo.Repository", "type:demo.Repository#findAll()", RelationKind.DECLARES));
        graph.addEdge(edge("endpoint:GET:/orders", "type:demo.Controller#list()", RelationKind.EXPOSES));
        graph.addEdge(new GraphEdge("type:demo.Controller#list()", "type:demo.Repository#findAll()", RelationKind.CALLS,
                Map.of(), new Provenance("CALL", callConfidence, SOURCE.file(), SOURCE.line(), SOURCE.column())));
        graph.addEdge(edge("type:demo.Repository", "table:orders", RelationKind.PERSISTS));
        return graph;
    }

    private static void declareType(CodeGraph graph, String id, EntityKind kind) {
        String file = "file:" + id;
        graph.upsertNode(new GraphNode(file, EntityKind.FILE, file, Map.of(), SOURCE));
        graph.upsertNode(new GraphNode(id, kind, id.substring("type:".length()), Map.of(), SOURCE));
        graph.addEdge(edge(file, id, RelationKind.DECLARES));
    }

    private static GraphEdge edge(String from, String to, RelationKind kind) {
        return new GraphEdge(from, to, kind, Map.of(), SOURCE);
    }
}

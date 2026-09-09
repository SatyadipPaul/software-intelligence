package io.softwareintelligence.evaluation;

import io.softwareintelligence.indextree.IndexTree;
import io.softwareintelligence.indextree.IndexTreeBuilder;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.Provenance;
import io.softwareintelligence.model.RelationKind;
import io.softwareintelligence.queryengine.RetrievalMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalHarnessTest {
    private static final Provenance SOURCE = new Provenance("JDT_AST", 1.0, "src/main/java/demo/Demo.java", 5, 1);

    @Test void retrieval_is_scored_from_the_question_alone_not_from_the_declared_subject() {
        CodeGraph graph = commerceGraph();
        IndexTree tree = IndexTreeBuilder.derive(graph);

        RetrievalHarness.Report report = new RetrievalHarness(10, 5, 4)
                .run(graph, tree, List.of(question("q1", "What breaks if PaymentService changes?", "PaymentService")),
                        RetrievalMode.HYBRID);

        assertEquals(1, report.results().size());
        assertTrue(report.results().get(0).found(), report.results().get(0).returned().toString());
        assertEquals(1.0, report.recallAt(1));
        assertEquals(1.0, report.meanReciprocalRank());
    }

    @Test void a_question_whose_subject_is_missing_is_skipped_rather_than_counted_as_a_miss() {
        CodeGraph graph = commerceGraph();

        RetrievalHarness.Report report = new RetrievalHarness(10, 5, 4)
                .run(graph, IndexTreeBuilder.derive(graph),
                        List.of(question("q1", "What breaks if NoSuchType changes?", "NoSuchType")), RetrievalMode.BM25);

        assertTrue(report.results().isEmpty());
        assertEquals(1, report.unusable().size());
    }

    @Test void a_miss_reports_what_was_wanted_and_what_came_back() {
        CodeGraph graph = commerceGraph();

        RetrievalHarness.Report report = new RetrievalHarness(10, 1, 4)
                .run(graph, IndexTreeBuilder.derive(graph),
                        List.of(question("q1", "which tables exist", "PaymentService")), RetrievalMode.BM25);

        String rendered = RetrievalHarness.render(report);
        if (!report.results().get(0).found()) {
            assertTrue(rendered.contains("wanted type:demo.PaymentService"), rendered);
            assertTrue(rendered.contains("got"), rendered);
        }
        assertTrue(rendered.contains("anchor recall"), rendered);
    }

    @Test void precision_at_k_is_declined_in_the_report_rather_than_faked() {
        CodeGraph graph = commerceGraph();

        RetrievalHarness.Report report = new RetrievalHarness(10, 5, 4)
                .run(graph, IndexTreeBuilder.derive(graph),
                        List.of(question("q1", "What breaks if PaymentService changes?", "PaymentService")),
                        RetrievalMode.TREE);

        assertTrue(RetrievalHarness.render(report).contains("precision@k                  not reported"));
    }

    @Test void landing_on_a_member_of_the_right_type_counts_as_reaching_it() {
        assertTrue(RetrievalHarness.reaches("type:demo.PaymentService#authorize()", "type:demo.PaymentService"));
        assertTrue(RetrievalHarness.reaches("type:demo.PaymentService", "type:demo.PaymentService#authorize()"));
        assertFalse(RetrievalHarness.reaches("type:demo.OrderService", "type:demo.PaymentService"));
    }

    @Test void every_mode_scores_the_same_questions_so_the_comparison_is_of_retrieval_alone() {
        CodeGraph graph = commerceGraph();
        IndexTree tree = IndexTreeBuilder.derive(graph);
        List<GroundedQuestion> set = List.of(
                question("q1", "What breaks if PaymentService changes?", "PaymentService"),
                question("q2", "Who calls OrderService?", "OrderService"));
        RetrievalHarness harness = new RetrievalHarness(10, 5, 4);

        for (RetrievalMode mode : RetrievalMode.values()) {
            assertEquals(2, harness.run(graph, tree, set, mode).results().size(), mode.name());
        }
    }

    private static GroundedQuestion question(String id, String text, String subject) {
        return new GroundedQuestion(id, "demo", text, GroundedQuestion.Kind.IMPACT, subject,
                List.of(), List.of(), 0.5, false);
    }

    private static CodeGraph commerceGraph() {
        CodeGraph graph = new CodeGraph();
        declareType(graph, "type:demo.PaymentService", EntityKind.SERVICE);
        declareType(graph, "type:demo.OrderService", EntityKind.SERVICE);
        graph.upsertNode(new GraphNode("module:demo", EntityKind.MODULE, "demo", Map.of(), SOURCE), true);
        graph.addEdge(new GraphEdge("module:demo", "type:demo.PaymentService", RelationKind.CONTAINS, Map.of(), SOURCE));
        graph.addEdge(new GraphEdge("module:demo", "type:demo.OrderService", RelationKind.CONTAINS, Map.of(), SOURCE));
        return graph;
    }

    private static void declareType(CodeGraph graph, String id, EntityKind kind) {
        String qualified = id.substring("type:".length());
        String file = "src/main/java/demo/" + qualified.substring(qualified.lastIndexOf('.') + 1) + ".java";
        Provenance provenance = new Provenance("JDT_AST", 1.0, file, 5, 1);
        graph.upsertNode(new GraphNode("file:" + file, EntityKind.FILE, file, Map.of(), provenance), true);
        graph.upsertNode(new GraphNode("package:demo", EntityKind.PACKAGE, "demo", Map.of(), provenance), true);
        graph.upsertNode(new GraphNode(id, kind, qualified, Map.of(), provenance), true);
        graph.addEdge(new GraphEdge("file:" + file, "package:demo", RelationKind.DECLARES, Map.of(), provenance));
        graph.addEdge(new GraphEdge("file:" + file, id, RelationKind.DECLARES, Map.of(), provenance));
    }
}

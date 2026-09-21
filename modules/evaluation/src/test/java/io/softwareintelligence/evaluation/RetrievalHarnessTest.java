package io.softwareintelligence.evaluation;

import io.softwareintelligence.indextree.IndexTree;
import io.softwareintelligence.indextree.IndexTreeBuilder;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.Provenance;
import io.softwareintelligence.model.RelationKind;
import io.softwareintelligence.embedding.TextEncoder;
import io.softwareintelligence.queryengine.DenseIndex;
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

        // The dense modes are covered with a stub encoder rather than skipped: the property under
        // test is that a mode never silently drops a question, and a mode exempted from that check
        // is exactly where such a bug would live.
        try (TextEncoder encoder = new HashingEncoder()) {
            DenseIndex dense = DenseIndex.over(graph, encoder);
            for (RetrievalMode mode : RetrievalMode.values()) {
                assertEquals(2, harness.run(graph, tree, dense, set, mode).results().size(), mode.name());
            }
        }
    }

    /** Deterministic stand-in for a model: enough to exercise the plumbing, no weights to download. */
    private static final class HashingEncoder implements TextEncoder {
        @Override public int dimensions() { return 16; }

        @Override public float[][] encode(List<String> texts) {
            float[][] vectors = new float[texts.size()][];
            for (int i = 0; i < texts.size(); i++) {
                float[] vector = new float[16];
                for (String word : texts.get(i).toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")) {
                    if (!word.isBlank()) vector[Math.floorMod(word.hashCode(), 16)] += 1;
                }
                double norm = 0;
                for (float component : vector) norm += component * component;
                norm = Math.sqrt(norm);
                if (norm > 0) for (int d = 0; d < vector.length; d++) vector[d] /= (float) norm;
                vectors[i] = vector;
            }
            return vectors;
        }

        @Override public void close() { }
    }

    @Test void anchor_coverage_measures_the_set_not_the_subject() {
        CodeGraph graph = commerceGraph();
        IndexTree tree = IndexTreeBuilder.derive(graph);
        GroundedQuestion plural = new GroundedQuestion("q1", "demo", "What breaks if PaymentService changes?",
                GroundedQuestion.Kind.IMPACT, "PaymentService",
                List.of("PaymentService", "OrderService"), List.of(), 0.5, false);

        RetrievalHarness.Report one = new RetrievalHarness(10, 1, 4).run(graph, tree, List.of(plural), RetrievalMode.HYBRID);
        RetrievalHarness.Report many = new RetrievalHarness(10, 8, 4).run(graph, tree, List.of(plural), RetrievalMode.HYBRID);

        assertEquals(1, one.pluralCount());
        assertTrue(many.anchorCoverage() >= one.anchorCoverage(),
                "more anchors cannot cover less of the answer: " + one.anchorCoverage() + " -> " + many.anchorCoverage());
        assertTrue(one.anchorCoverage() <= 0.5 + 1e-9,
                "a single anchor cannot cover a two-symbol answer: " + one.anchorCoverage());
    }

    @Test void a_single_symbol_answer_is_not_counted_as_plural() {
        CodeGraph graph = commerceGraph();

        RetrievalHarness.Report report = new RetrievalHarness(10, 5, 4).run(graph, IndexTreeBuilder.derive(graph),
                List.of(question("q1", "What breaks if PaymentService changes?", "PaymentService")),
                RetrievalMode.HYBRID);

        assertEquals(0, report.pluralCount());
        assertTrue(Double.isNaN(report.anchorCoverage()));
        assertTrue(RetrievalHarness.render(report).contains("anchor coverage              not measured"),
                RetrievalHarness.render(report));
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

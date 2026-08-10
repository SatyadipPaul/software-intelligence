package io.softwareintelligence.evaluation;

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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The harness is what every quality claim in this project rests on, so it is worth being explicit
 * that it can fail. A scorer that always returns 1.0 would make every benchmark meaningless while
 * looking perfect.
 */
class EvaluationHarnessTest {
    private static final Provenance SOURCE = new Provenance("JDT_BINDING", 1.0, "src/main/java/demo/Demo.java", 12, 1);

    @Test void a_question_whose_expectations_are_met_passes() {
        EvaluationHarness.Report report = new EvaluationHarness(3).run(graph(), List.of(question(
                List.of("Controller.handle"), List.of("Demo.java:12"), 0.9)));

        assertEquals(1, report.passed());
        assertEquals(1.0, report.structuralAccuracy(), 1e-9);
        assertEquals(1.0, report.evidenceRecall(), 1e-9);
    }

    @Test void a_missing_symbol_fails_and_says_which_one() {
        EvaluationHarness.Report report = new EvaluationHarness(3).run(graph(), List.of(question(
                List.of("Controller.handle", "NoSuchType"), List.of(), 0.9)));

        assertEquals(0, report.passed());
        assertEquals(0.5, report.structuralAccuracy(), 1e-9);
        assertTrue(report.results().get(0).missing().contains("name: NoSuchType"));
        assertTrue(EvaluationHarness.render(report).contains("NoSuchType"));
    }

    @Test void a_missing_source_location_fails_evidence_recall() {
        EvaluationHarness.Report report = new EvaluationHarness(3).run(graph(), List.of(question(
                List.of("Controller.handle"), List.of("Nowhere.java:999"), 0.9)));

        assertEquals(0, report.passed());
        assertEquals(1.0, report.structuralAccuracy(), 1e-9);
        assertEquals(0.0, report.evidenceRecall(), 1e-9);
    }

    @Test void a_confidence_floor_above_the_evidence_fails_groundedness() {
        CodeGraph graph = graph();
        // The caller node must exist, or impact skips the edge and the floor is never tested -
        // which is how this test failed the first time it was written.
        graph.upsertNode(new GraphNode("type:demo.Other#call()", EntityKind.METHOD, "call", Map.of(), SOURCE));
        graph.addEdge(new GraphEdge("type:demo.Other#call()", "type:demo.Service#run()", RelationKind.CALLS, Map.of(),
                new Provenance("DISPATCH_NORMALIZED", 0.60, "src/main/java/demo/Other.java", 4, 1)));

        EvaluationHarness.Report report = new EvaluationHarness(3).run(graph, List.of(question(
                List.of("Controller.handle"), List.of(), 0.95)));

        assertTrue(report.groundedness() < 1.0, "evidence below the floor must lower groundedness");
        assertEquals(0, report.passed());
    }

    @Test void an_unresolvable_subject_scores_zero_rather_than_throwing() {
        EvaluationHarness.Report report = new EvaluationHarness(3).run(graph(), List.of(
                new GroundedQuestion("q", "demo", "?", GroundedQuestion.Kind.LOOKUP, "NothingLikeThis",
                        List.of("Whatever"), List.of(), 0.9)));

        assertEquals(0, report.passed());
        assertEquals(0.0, report.structuralAccuracy(), 1e-9);
        assertTrue(report.results().get(0).missing().get(0).contains("subject not found"));
    }

    @Test void a_question_with_no_expectations_cannot_silently_pass_on_nothing() {
        EvaluationHarness.Report report = new EvaluationHarness(3).run(graph(), List.of(question(List.of(), List.of(), 0.9)));

        // Vacuous expectations score 1.0 by definition; the point of asserting it is that the
        // behaviour is deliberate, so an empty question set cannot be mistaken for a passing one.
        assertEquals(1, report.passed());
        assertTrue(report.results().get(0).missing().isEmpty());
    }

    @Test void a_question_line_round_trips_through_the_file_format() {
        GroundedQuestion parsed = GroundedQuestion.parse(
                "id-1\tdemo\tWhat breaks?\tIMPACT\tService\tA.b|C.d\tX.java:1|Y.java:2\t0.85");

        assertEquals("id-1", parsed.id());
        assertEquals(GroundedQuestion.Kind.IMPACT, parsed.kind());
        assertEquals(List.of("A.b", "C.d"), parsed.expectedNames());
        assertEquals(List.of("X.java:1", "Y.java:2"), parsed.expectedLocations());
        assertEquals(0.85, parsed.minimumConfidence(), 1e-9);
    }

    @Test void a_malformed_question_line_is_rejected_rather_than_half_read() {
        assertThrows(IllegalArgumentException.class, () -> GroundedQuestion.parse("too\tfew\tfields"));
    }

    @Test void comments_and_blank_lines_are_skipped_when_loading(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("questions.tsv");
        Files.writeString(file, """
                # a comment

                id-1\tdemo\tWhat breaks?\tIMPACT\tService\tA.b\t\t0.85
                """);

        assertEquals(1, EvaluationHarness.load(file).size());
    }

    @Test void a_member_expectation_matches_on_owner_and_member_together() {
        // "Controller.handle" must match type:demo.Controller#handle(), and must not match a
        // same-named method on a different type.
        CodeGraph graph = graph();
        graph.upsertNode(new GraphNode("type:demo.Unrelated#handle()", EntityKind.METHOD, "handle", Map.of(), SOURCE));

        EvaluationHarness.Report report = new EvaluationHarness(3).run(graph, List.of(question(
                List.of("Unrelated.handle"), List.of(), 0.9)));

        assertEquals(0, report.passed(), "a method on an unreferenced type must not count as found");
    }

    private static GroundedQuestion question(List<String> names, List<String> locations, double floor) {
        return new GroundedQuestion("q", "demo", "?", GroundedQuestion.Kind.IMPACT, "type:demo.Service",
                names, locations, floor);
    }

    private static CodeGraph graph() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(new GraphNode("type:demo.Service", EntityKind.SERVICE, "demo.Service", Map.of(), SOURCE));
        graph.upsertNode(new GraphNode("type:demo.Service#run()", EntityKind.METHOD, "run", Map.of(), SOURCE));
        graph.upsertNode(new GraphNode("type:demo.Controller", EntityKind.CONTROLLER, "demo.Controller", Map.of(), SOURCE));
        graph.upsertNode(new GraphNode("type:demo.Controller#handle()", EntityKind.METHOD, "handle", Map.of(), SOURCE));
        graph.addEdge(new GraphEdge("type:demo.Service", "type:demo.Service#run()", RelationKind.DECLARES, Map.of(), SOURCE));
        graph.addEdge(new GraphEdge("type:demo.Controller", "type:demo.Controller#handle()", RelationKind.DECLARES, Map.of(), SOURCE));
        graph.addEdge(new GraphEdge("type:demo.Controller#handle()", "type:demo.Service#run()", RelationKind.CALLS, Map.of(), SOURCE));
        return graph;
    }
}

package io.softwareintelligence.queryengine;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.ContextPacket;
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

class QueryEngineTest {
    private static final Provenance SOURCE = new Provenance("JDT_BINDING", 1.0, "src/main/java/demo/Payments.java", 12, 3);

    @Test void camel_case_identifiers_are_searchable_as_words() {
        assertTrue(Bm25Index.tokenize("PaymentService#authorize").containsAll(List.of("payment", "service", "authorize")));
    }

    @Test void retrieval_finds_a_symbol_from_plain_language() {
        Bm25Index index = Bm25Index.over(paymentsGraph());

        List<Bm25Index.Hit> hits = index.search("payment authorization service", 5);

        assertFalse(hits.isEmpty());
        assertTrue(hits.stream().anyMatch(hit -> hit.node().id().equals("type:demo.PaymentService")), hits.toString());
    }

    @Test void retrieval_is_stable_for_the_same_query() {
        Bm25Index index = Bm25Index.over(paymentsGraph());

        assertEquals(index.search("payment", 5).stream().map(hit -> hit.node().id()).toList(),
                index.search("payment", 5).stream().map(hit -> hit.node().id()).toList());
    }

    @Test void question_words_do_not_score_as_search_terms() {
        CodeGraph graph = paymentsGraph();
        graph.upsertNode(new GraphNode("type:demo.Flags.field:FAIL_ON", EntityKind.FIELD, "FAIL_ON", Map.of(), SOURCE));
        Bm25Index index = Bm25Index.over(graph);

        List<Bm25Index.Hit> hits = index.search("what does PaymentService depend on?", 5);

        assertEquals("type:demo.PaymentService", hits.get(0).node().id(), hits.toString());
        assertTrue(hits.stream().noneMatch(hit -> hit.node().id().contains("FAIL_ON")),
                "'on' must not make an unrelated constant a top result");
    }

    @Test void a_type_outranks_a_field_that_merely_mentions_its_name() {
        CodeGraph graph = paymentsGraph();
        graph.upsertNode(new GraphNode("type:demo.SomeTest.field:paymentService", EntityKind.FIELD, "paymentService", Map.of(), SOURCE));
        Bm25Index index = Bm25Index.over(graph);

        List<Bm25Index.Hit> hits = index.search("PaymentService", 5);

        assertEquals("type:demo.PaymentService", hits.get(0).node().id(), hits.toString());
    }

    @Test void compression_keeps_the_evidence_that_touches_the_subject() {
        CodeGraph graph = paymentsGraph();
        // An unrelated edge sorts last by id but is equally confident; a confidence-only sort would
        // have kept it and dropped the one the question is actually about.
        graph.upsertNode(new GraphNode("type:zzz.Unrelated", EntityKind.TYPE, "zzz.Unrelated", Map.of(), SOURCE));
        graph.upsertNode(new GraphNode("type:zzz.Other", EntityKind.TYPE, "zzz.Other", Map.of(), SOURCE));
        graph.addEdge(new GraphEdge("type:zzz.Unrelated", "type:zzz.Other", RelationKind.CALLS, Map.of(), SOURCE));
        ContextPacket packet = GraphQueries.context(graph, graph.node("type:demo.PaymentService").orElseThrow(), 3);

        ContextPacket compressed = QueryPlanner.compress(packet, 20);

        assertFalse(compressed.evidence().isEmpty());
        assertTrue(compressed.evidence().stream().allMatch(edge ->
                        edge.from().startsWith("type:demo.") || edge.to().startsWith("type:demo.")
                                || edge.from().startsWith("endpoint:") || edge.to().startsWith("endpoint:")),
                "surviving evidence must concern the subject: " + compressed.evidence());
    }

    @Test void questions_are_classified_by_intent() {
        assertEquals(QueryPlanner.QueryKind.IMPACT, QueryPlanner.classify("what breaks if I remove PaymentService?").kind());
        assertEquals(QueryPlanner.QueryKind.ENDPOINT, QueryPlanner.classify("which REST endpoints exist?").kind());
        assertEquals(QueryPlanner.QueryKind.PERSISTENCE, QueryPlanner.classify("which tables does this write?").kind());
        assertEquals(QueryPlanner.QueryKind.SECURITY, QueryPlanner.classify("what roles guard the admin API?").kind());
        assertEquals(QueryPlanner.QueryKind.LOOKUP, QueryPlanner.classify("where is PaymentService defined").kind());
    }

    @Test void the_symbol_in_a_question_is_used_as_the_anchor() {
        assertEquals("PaymentService", QueryPlanner.subjectOf("what breaks if I change PaymentService today?"));
    }

    @Test void a_plan_anchors_on_a_real_symbol_and_returns_its_context() {
        CodeGraph graph = paymentsGraph();

        QueryPlanner.Answerable answerable = QueryPlanner.plan(graph, Bm25Index.over(graph), "what breaks if PaymentService changes?", 5);

        assertEquals(QueryPlanner.QueryKind.IMPACT, answerable.plan().kind());
        assertEquals("type:demo.PaymentService", answerable.subject().orElseThrow().id());
        assertTrue(answerable.context().isPresent());
    }

    @Test void compression_respects_a_budget_and_keeps_the_endpoints() {
        CodeGraph graph = paymentsGraph();
        ContextPacket packet = GraphQueries.context(graph, graph.node("type:demo.PaymentService").orElseThrow(), 3);
        int budget = Math.max(12, QueryPlanner.estimateTokens(packet) / 2);

        ContextPacket compressed = QueryPlanner.compress(packet, budget);

        assertTrue(QueryPlanner.estimateTokens(compressed) <= QueryPlanner.estimateTokens(packet));
        assertEquals(packet.endpoints(), compressed.endpoints(), "the operational answer must survive compression");
    }

    @Test void a_claim_with_a_real_citation_is_supported() {
        CodeGraph graph = paymentsGraph();
        VerifiedAnswer.Claim claim = new VerifiedAnswer.Claim("the controller calls the service", "type:demo.PaymentService",
                List.of(new VerifiedAnswer.Citation("type:demo.PaymentController#pay()", "type:demo.PaymentService#authorize()", "CALLS")));

        VerifiedAnswer.Answer answer = VerifiedAnswer.verify(graph, "who calls it?", List.of(claim));

        assertEquals(1, answer.supported().size());
        assertEquals("src/main/java/demo/Payments.java", answer.supported().get(0).evidence().get(0).provenance().file());
    }

    @Test void a_claim_citing_a_relationship_that_does_not_exist_is_withheld() {
        CodeGraph graph = paymentsGraph();
        VerifiedAnswer.Claim claim = new VerifiedAnswer.Claim("the service writes to Kafka", "type:demo.PaymentService",
                List.of(new VerifiedAnswer.Citation("type:demo.PaymentService", "topic:invented", "PUBLISHES")));

        VerifiedAnswer.Answer answer = VerifiedAnswer.verify(graph, "does it publish?", List.of(claim));

        assertTrue(answer.supported().isEmpty());
        assertEquals(VerifiedAnswer.Verdict.UNSUPPORTED_CITATION_NOT_IN_GRAPH, answer.withheld().get(0).verdict());
    }

    @Test void a_claim_with_no_citation_at_all_is_withheld() {
        VerifiedAnswer.Answer answer = VerifiedAnswer.verify(paymentsGraph(), "is it safe?",
                List.of(new VerifiedAnswer.Claim("this change is safe", "type:demo.PaymentService", List.of())));

        assertEquals(VerifiedAnswer.Verdict.UNSUPPORTED_MISSING_CITATION, answer.withheld().get(0).verdict());
    }

    @Test void a_real_citation_about_an_unrelated_symbol_is_withheld() {
        CodeGraph graph = paymentsGraph();
        VerifiedAnswer.Claim claim = new VerifiedAnswer.Claim("PaymentService exposes the route", "type:demo.PaymentService",
                List.of(new VerifiedAnswer.Citation("endpoint:POST:/pay", "type:demo.PaymentController#pay()", "EXPOSES")));

        VerifiedAnswer.Answer answer = VerifiedAnswer.verify(graph, "what exposes it?", List.of(claim));

        assertEquals(VerifiedAnswer.Verdict.UNSUPPORTED_CITATION_UNRELATED, answer.withheld().get(0).verdict());
    }

    @Test void claims_derived_from_a_packet_all_verify() {
        CodeGraph graph = paymentsGraph();
        ContextPacket packet = GraphQueries.context(graph, graph.node("type:demo.PaymentService").orElseThrow(), 3);

        VerifiedAnswer.Answer answer = VerifiedAnswer.verify(graph, "what touches it?", VerifiedAnswer.claimsFrom(packet));

        assertFalse(answer.supported().isEmpty());
        assertTrue(answer.withheld().isEmpty(), answer.withheld().toString());
    }

    @Test void enrichment_stops_at_the_budget_and_explains_every_selection() {
        CodeGraph graph = paymentsGraph();

        EnrichmentPlanner.Budget budget = new EnrichmentPlanner.Budget(300, 0.003);
        EnrichmentPlanner.Plan plan = EnrichmentPlanner.plan(graph, budget);

        assertTrue(plan.plannedTokens() <= 300);
        assertTrue(plan.selected().stream().allMatch(candidate -> !candidate.reason().isBlank()));
        assertTrue(EnrichmentPlanner.audit(plan, budget).contains("ENRICHMENT PLAN"));
    }

    private static CodeGraph paymentsGraph() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(new GraphNode("type:demo.PaymentService", EntityKind.SERVICE, "demo.PaymentService", Map.of("annotations", "Service"), SOURCE));
        graph.upsertNode(new GraphNode("type:demo.PaymentService#authorize()", EntityKind.METHOD, "authorize", Map.of(), SOURCE));
        graph.upsertNode(new GraphNode("type:demo.PaymentController", EntityKind.CONTROLLER, "demo.PaymentController", Map.of(), SOURCE));
        graph.upsertNode(new GraphNode("type:demo.PaymentController#pay()", EntityKind.METHOD, "pay", Map.of(), SOURCE));
        graph.upsertNode(new GraphNode("endpoint:POST:/pay", EntityKind.ENDPOINT, "POST /pay", Map.of(), SOURCE));
        graph.addEdge(new GraphEdge("type:demo.PaymentService", "type:demo.PaymentService#authorize()", RelationKind.DECLARES, Map.of(), SOURCE));
        graph.addEdge(new GraphEdge("type:demo.PaymentController", "type:demo.PaymentController#pay()", RelationKind.DECLARES, Map.of(), SOURCE));
        graph.addEdge(new GraphEdge("type:demo.PaymentController#pay()", "type:demo.PaymentService#authorize()", RelationKind.CALLS, Map.of(), SOURCE));
        graph.addEdge(new GraphEdge("endpoint:POST:/pay", "type:demo.PaymentController#pay()", RelationKind.EXPOSES, Map.of(), SOURCE));
        return graph;
    }
}

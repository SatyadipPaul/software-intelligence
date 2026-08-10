package io.softwareintelligence.queryengine;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphJsonWriter;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.Provenance;
import io.softwareintelligence.model.RelationKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrichmentTest {
    private static final Provenance SOURCE = new Provenance("JDT_BINDING", 1.0, "src/main/java/demo/A.java", 9, 1);

    @Test void a_supported_claim_becomes_an_attribute_and_changes_nothing_else() {
        CodeGraph graph = graph();
        GraphNode before = graph.node("type:demo.Service").orElseThrow();

        EnrichmentMerge.Result result = EnrichmentMerge.apply(graph, List.of(claim(
                "type:demo.Service", EnrichmentClaims.ClaimKind.SUMMARY, "settles a customer invoice", 0.7,
                new VerifiedAnswer.Citation("type:demo.Controller", "type:demo.Service", "CALLS"))));

        assertEquals(1, result.applied());
        GraphNode after = graph.node("type:demo.Service").orElseThrow();
        assertEquals("settles a customer invoice", after.attributes().get("claim.summary"));
        assertEquals(before.kind(), after.kind());
        assertEquals(before.name(), after.name());
        assertEquals(before.provenance(), after.provenance(), "enrichment must not touch provenance");
    }

    @Test void a_fabricated_citation_is_rejected() {
        CodeGraph graph = graph();

        EnrichmentMerge.Result result = EnrichmentMerge.apply(graph, List.of(claim(
                "type:demo.Service", EnrichmentClaims.ClaimKind.SUMMARY, "calls an external gateway", 0.8,
                new VerifiedAnswer.Citation("type:demo.Service", "service:invented", "DEPENDS_ON"))));

        assertEquals(0, result.applied());
        assertEquals(1, result.rejected().size());
        assertTrue(result.rejected().get(0).reason().contains("does not exist"));
        assertFalse(graph.node("type:demo.Service").orElseThrow().attributes().containsKey("claim.summary"));
    }

    @Test void a_real_citation_about_a_different_symbol_is_rejected() {
        CodeGraph graph = graph();

        EnrichmentMerge.Result result = EnrichmentMerge.apply(graph, List.of(claim(
                "type:demo.Service", EnrichmentClaims.ClaimKind.SUMMARY, "describes something else", 0.8,
                new VerifiedAnswer.Citation("endpoint:GET:/a", "type:demo.Controller", "EXPOSES"))));

        assertEquals(0, result.applied());
        assertTrue(result.rejected().get(0).reason().contains("none of them involves"));
    }

    @Test void confidence_is_capped_in_code_because_a_model_may_ignore_the_instruction() {
        CodeGraph graph = graph();

        EnrichmentMerge.apply(graph, List.of(claim(
                "type:demo.Service", EnrichmentClaims.ClaimKind.RULE, "always audited", 0.99,
                new VerifiedAnswer.Citation("type:demo.Controller", "type:demo.Service", "CALLS"))));

        assertEquals(Double.toString(EnrichmentClaims.MAX_CLAIM_CONFIDENCE),
                graph.node("type:demo.Service").orElseThrow().attributes().get("claim.rule.confidence"));
    }

    @Test void a_dispute_is_surfaced_and_never_applied() {
        CodeGraph graph = graph();

        EnrichmentMerge.Result result = EnrichmentMerge.apply(graph, List.of(claim(
                "type:demo.Service", EnrichmentClaims.ClaimKind.DISPUTE, "this call does not really happen", 0.8,
                new VerifiedAnswer.Citation("type:demo.Controller", "type:demo.Service", "CALLS"))));

        assertEquals(0, result.applied());
        assertEquals(1, result.disputes().size());
        assertFalse(result.clean());
        assertFalse(graph.node("type:demo.Service").orElseThrow().attributes().containsKey("claim.dispute"));
        assertTrue(EnrichmentMerge.report(result).contains("the deterministic model stands"));
    }

    @Test void enrichment_never_adds_an_edge() {
        CodeGraph graph = graph();
        int edges = graph.edges().size();

        EnrichmentMerge.apply(graph, List.of(claim(
                "type:demo.Service", EnrichmentClaims.ClaimKind.RULE, "settles invoices", 0.7,
                new VerifiedAnswer.Citation("type:demo.Controller", "type:demo.Service", "CALLS"))));

        assertEquals(edges, graph.edges().size(), "a model may describe the graph, never extend it");
    }

    @Test void stripping_returns_the_graph_to_its_deterministic_form() {
        CodeGraph graph = graph();
        String deterministic = GraphJsonWriter.write(graph);
        EnrichmentMerge.apply(graph, List.of(claim(
                "type:demo.Service", EnrichmentClaims.ClaimKind.SUMMARY, "settles invoices", 0.7,
                new VerifiedAnswer.Citation("type:demo.Controller", "type:demo.Service", "CALLS"))));

        assertEquals(1, EnrichmentMerge.strip(graph));

        assertEquals(deterministic, GraphJsonWriter.write(graph));
    }

    @Test void applying_the_same_claims_twice_gives_the_same_graph() {
        CodeGraph first = graph(), second = graph();
        List<EnrichmentClaims.Claim> claims = List.of(claim(
                "type:demo.Service", EnrichmentClaims.ClaimKind.SUMMARY, "settles invoices", 0.7,
                new VerifiedAnswer.Citation("type:demo.Controller", "type:demo.Service", "CALLS")));

        EnrichmentMerge.apply(first, claims);
        EnrichmentMerge.apply(second, claims);

        assertEquals(GraphJsonWriter.write(first), GraphJsonWriter.write(second),
                "graph + pinned claims must be reproducible even though the enricher was not");
    }

    @Test void claims_round_trip_through_the_file_format() {
        List<EnrichmentClaims.Claim> claims = List.of(
                claim("type:demo.Service", EnrichmentClaims.ClaimKind.SUMMARY, "settles a \"customer\" invoice", 0.7,
                        new VerifiedAnswer.Citation("type:demo.Controller", "type:demo.Service", "CALLS")),
                claim("type:demo.Controller", EnrichmentClaims.ClaimKind.DISPUTE, "line 9 looks wrong", 0.6,
                        new VerifiedAnswer.Citation("endpoint:GET:/a", "type:demo.Controller", "EXPOSES")));

        List<EnrichmentClaims.Claim> parsed = EnrichmentClaims.parse(EnrichmentClaims.write(claims));

        assertEquals(2, parsed.size());
        assertEquals("settles a \"customer\" invoice",
                parsed.stream().filter(c -> c.kind() == EnrichmentClaims.ClaimKind.SUMMARY).findFirst().orElseThrow().value());
        assertEquals(1, parsed.get(0).citations().size());
    }

    @Test void the_claims_file_is_written_in_a_stable_order() {
        List<EnrichmentClaims.Claim> claims = List.of(
                claim("type:demo.Z", EnrichmentClaims.ClaimKind.SUMMARY, "z", 0.5),
                claim("type:demo.A", EnrichmentClaims.ClaimKind.SUMMARY, "a", 0.5));

        String written = EnrichmentClaims.write(claims);

        assertTrue(written.indexOf("type:demo.A") < written.indexOf("type:demo.Z"),
                "a re-run must diff cleanly against the previous claims file");
    }

    @Test void a_work_packet_exposes_only_the_relationships_the_agent_may_cite() {
        CodeGraph graph = graph();
        // Built directly rather than via ranking: a well-annotated, lightly-referenced symbol is
        // deliberately *not* an enrichment candidate, which is the planner working as intended.
        List<EnrichmentPlanner.Candidate> candidates =
                List.of(new EnrichmentPlanner.Candidate("type:demo.Service", 5.0, 140, "test"));

        List<EnrichmentClaims.WorkPacket> packets = EnrichmentClaims.workPackets(graph, candidates);

        assertEquals(1, packets.size());
        EnrichmentClaims.WorkPacket packet = packets.get(0);
        assertTrue(packet.citableRelationships().stream().anyMatch(row -> row.contains("CALLS")));
        assertTrue(packet.citableRelationships().stream().noneMatch(row -> row.contains("service:invented")));
        assertTrue(packet.facts().get("declaredAt").contains("A.java:9"));
    }

    @Test void a_library_symbol_this_repository_only_calls_is_not_an_enrichment_candidate() {
        CodeGraph graph = graph();
        // A called library method is recorded as a METHOD, not an EXTERNAL_SYMBOL, so kind alone
        // cannot tell it from our own code. Heavily used ones would otherwise dominate the budget.
        graph.upsertNode(new GraphNode("type:org.junit.jupiter.api.Assertions#assertEquals(java.lang.Object)",
                EntityKind.METHOD, "assertEquals", Map.of("resolved", "true"), SOURCE));
        for (int i = 0; i < 30; i++) {
            graph.upsertNode(new GraphNode("type:demo.T" + i + "#t()", EntityKind.METHOD, "t", Map.of(), SOURCE));
            graph.addEdge(new GraphEdge("type:demo.T" + i + "#t()",
                    "type:org.junit.jupiter.api.Assertions#assertEquals(java.lang.Object)", RelationKind.CALLS, Map.of(), SOURCE));
        }

        List<EnrichmentPlanner.Candidate> ranked = EnrichmentPlanner.rank(graph);

        assertTrue(ranked.stream().noneMatch(candidate -> candidate.id().contains("org.junit")),
                "a library this repository merely calls must not be enriched: " + ranked);
    }

    @Test void a_symbol_this_repository_declares_is_a_candidate() {
        CodeGraph graph = graph();
        graph.upsertNode(new GraphNode("type:demo.Service#run()", EntityKind.METHOD, "run", Map.of(), SOURCE));
        graph.addEdge(new GraphEdge("type:demo.Service", "type:demo.Service#run()", RelationKind.DECLARES, Map.of(), SOURCE));
        graph.addEdge(new GraphEdge("type:demo.Controller", "type:demo.Service#run()", RelationKind.CALLS, Map.of(),
                new Provenance("DISPATCH_NORMALIZED", 0.6, "A.java", 3, 1)));

        assertTrue(EnrichmentPlanner.rank(graph).stream().anyMatch(candidate -> candidate.id().equals("type:demo.Service#run()")));
    }

    private static EnrichmentClaims.Claim claim(String subject, EnrichmentClaims.ClaimKind kind, String value,
                                                double confidence, VerifiedAnswer.Citation... citations) {
        return new EnrichmentClaims.Claim(subject, kind, value, List.of(citations), "haiku", confidence);
    }

    private static CodeGraph graph() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(new GraphNode("type:demo.Service", EntityKind.SERVICE, "demo.Service", Map.of("annotations", "Service"), SOURCE));
        graph.upsertNode(new GraphNode("type:demo.Controller", EntityKind.CONTROLLER, "demo.Controller", Map.of(), SOURCE));
        graph.upsertNode(new GraphNode("endpoint:GET:/a", EntityKind.ENDPOINT, "GET /a", Map.of(), SOURCE));
        graph.addEdge(new GraphEdge("type:demo.Controller", "type:demo.Service", RelationKind.CALLS, Map.of(), SOURCE));
        graph.addEdge(new GraphEdge("endpoint:GET:/a", "type:demo.Controller", RelationKind.EXPOSES, Map.of(), SOURCE));
        return graph;
    }
}

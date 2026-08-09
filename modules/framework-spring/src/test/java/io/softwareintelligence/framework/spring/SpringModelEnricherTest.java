package io.softwareintelligence.framework.spring;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.Provenance;
import io.softwareintelligence.model.RelationKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringModelEnricherTest {
    private static final Provenance SOURCE = Provenance.syntax("Demo.java", 7, 1);

    @Test void an_injected_interface_resolves_to_its_single_component_implementation() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(node("type:demo.Ledger", EntityKind.INTERFACE, Map.of()));
        graph.upsertNode(node("type:demo.SqlLedger", EntityKind.SERVICE, Map.of("annotations", "Service")));
        graph.addEdge(edge("type:demo.SqlLedger", "type:demo.Ledger", RelationKind.IMPLEMENTS, Map.of()));
        graph.upsertNode(node("type:demo.Books", EntityKind.SERVICE, Map.of("annotations", "Service")));
        graph.addEdge(edge("type:demo.Books#<init>(demo.Ledger)", "type:demo.Ledger", RelationKind.DEPENDS_ON,
                Map.of("injection", "constructor", "parameter", "ledger")));

        new SpringModelEnricher().enrich(graph);

        GraphEdge wired = graph.outgoing("type:demo.Books").stream()
                .filter(edge -> edge.provenance().resolver().equals(SpringModelEnricher.BEAN_RESOLVER))
                .findFirst().orElseThrow(() -> new AssertionError("no bean wiring edge"));
        assertEquals("type:demo.SqlLedger", wired.to());
        assertEquals(0.95, wired.provenance().confidence(), 1e-9);
        assertEquals("1", wired.attributes().get("candidates"));
    }

    @Test void two_candidate_beans_are_both_reported_at_lower_confidence() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(node("type:demo.Ledger", EntityKind.INTERFACE, Map.of()));
        graph.upsertNode(node("type:demo.SqlLedger", EntityKind.SERVICE, Map.of("annotations", "Service")));
        graph.upsertNode(node("type:demo.MemoryLedger", EntityKind.SERVICE, Map.of("annotations", "Service")));
        graph.addEdge(edge("type:demo.SqlLedger", "type:demo.Ledger", RelationKind.IMPLEMENTS, Map.of()));
        graph.addEdge(edge("type:demo.MemoryLedger", "type:demo.Ledger", RelationKind.IMPLEMENTS, Map.of()));
        graph.upsertNode(node("type:demo.Books", EntityKind.SERVICE, Map.of("annotations", "Service")));
        graph.addEdge(edge("type:demo.Books#<init>(demo.Ledger)", "type:demo.Ledger", RelationKind.DEPENDS_ON, Map.of("injection", "constructor")));

        new SpringModelEnricher().enrich(graph);

        List<GraphEdge> wired = graph.outgoing("type:demo.Books").stream()
                .filter(edge -> edge.provenance().resolver().equals(SpringModelEnricher.BEAN_RESOLVER)).toList();
        assertEquals(2, wired.size());
        assertTrue(wired.stream().allMatch(edge -> edge.provenance().confidence() < 0.95));
    }

    @Test void a_non_component_implementation_is_not_wired() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(node("type:demo.Ledger", EntityKind.INTERFACE, Map.of()));
        graph.upsertNode(node("type:demo.PlainLedger", EntityKind.TYPE, Map.of("annotations", "")));
        graph.addEdge(edge("type:demo.PlainLedger", "type:demo.Ledger", RelationKind.IMPLEMENTS, Map.of()));
        graph.upsertNode(node("type:demo.Books", EntityKind.SERVICE, Map.of("annotations", "Service")));
        graph.addEdge(edge("type:demo.Books#<init>(demo.Ledger)", "type:demo.Ledger", RelationKind.DEPENDS_ON, Map.of("injection", "constructor")));

        new SpringModelEnricher().enrich(graph);

        assertFalse(graph.edges().stream().anyMatch(edge -> edge.provenance().resolver().equals(SpringModelEnricher.BEAN_RESOLVER)));
    }

    @Test void a_guard_annotation_becomes_a_node_that_points_at_what_it_protects() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(node("type:demo.Admin#purge()", EntityKind.METHOD,
                Map.of("annotation.PreAuthorize", "", "annotation.PreAuthorize.value", "hasRole('ADMIN')")));

        new SpringModelEnricher().enrich(graph);

        GraphNode guard = graph.node("guard:PreAuthorize:hasRole('ADMIN')").orElseThrow();
        assertEquals(EntityKind.SECURITY_GUARD, guard.kind());
        assertTrue(graph.outgoing(guard.id()).stream()
                .anyMatch(edge -> edge.kind() == RelationKind.CONFIGURES && edge.to().equals("type:demo.Admin#purge()")));
    }

    @Test void a_jpql_query_links_the_method_to_the_entity_table() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(node("type:demo.Vet", EntityKind.ENTITY, Map.of()));
        graph.upsertNode(node("table:vets", EntityKind.DATABASE_TABLE, Map.of()));
        graph.addEdge(edge("type:demo.Vet", "table:vets", RelationKind.PERSISTS, Map.of()));
        graph.upsertNode(node("type:demo.VetRepository#findAll()", EntityKind.METHOD,
                Map.of("annotation.Query.value", "SELECT DISTINCT vet FROM Vet vet LEFT JOIN vet.specialties")));

        new SpringModelEnricher().enrich(graph);

        assertTrue(graph.outgoing("type:demo.VetRepository#findAll()").stream()
                .anyMatch(edge -> edge.kind() == RelationKind.PERSISTS && edge.to().equals("table:vets")),
                "a JPQL entity name must resolve through the entity to its table");
    }

    @Test void a_configuration_property_placeholder_is_recorded_without_its_default() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(node("type:demo.Client.field:url", EntityKind.FIELD, Map.of("annotation.Value.value", "${billing.url:http://localhost}")));

        new SpringModelEnricher().enrich(graph);

        assertTrue(graph.node("property:billing.url").isPresent(), graph.nodes().toString());
    }

    @Test void a_literal_kafka_topic_becomes_a_publish_relationship() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(node("type:demo.Emitter#emit()", EntityKind.METHOD, Map.of()));
        graph.addEdge(edge("type:demo.Emitter#emit()", "type:org.springframework.kafka.core.KafkaTemplate#send(java.lang.String)",
                RelationKind.CALLS, Map.of("arg0", "payments.authorized")));

        new SpringModelEnricher().enrich(graph);

        assertTrue(graph.outgoing("type:demo.Emitter#emit()").stream()
                .anyMatch(edge -> edge.kind() == RelationKind.PUBLISHES && edge.to().equals("topic:payments.authorized")));
    }

    @Test void a_feign_client_becomes_an_external_service_dependency() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(node("type:demo.BillingClient", EntityKind.INTERFACE,
                Map.of("annotation.FeignClient.name", "billing", "annotation.FeignClient.url", "https://billing.internal")));

        new SpringModelEnricher().enrich(graph);

        GraphNode service = graph.node("service:billing").orElseThrow();
        assertEquals(EntityKind.EXTERNAL_SERVICE, service.kind());
        assertEquals("https://billing.internal", service.attributes().get("url"));
    }

    @Test void sql_table_extraction_reads_only_the_names_a_statement_states() {
        assertEquals(Set.of("owners"), SqlTables.referenced("SELECT * FROM owners WHERE id = ?"));
        assertEquals(Set.of("Vet"), SqlTables.referenced("SELECT DISTINCT v FROM Vet v LEFT JOIN v.specialties"));
        assertEquals(Set.of("pets"), SqlTables.referenced("UPDATE pets SET name = ?"));
        assertEquals(Set.of("visits"), SqlTables.referenced("INSERT INTO visits (id) VALUES (?)"));
        assertTrue(SqlTables.referenced("SELECT 1").isEmpty());
    }

    @Test void enrichment_never_removes_a_deterministic_edge() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(node("type:demo.Ledger", EntityKind.INTERFACE, Map.of()));
        graph.upsertNode(node("type:demo.SqlLedger", EntityKind.SERVICE, Map.of("annotations", "Service")));
        graph.addEdge(edge("type:demo.SqlLedger", "type:demo.Ledger", RelationKind.IMPLEMENTS, Map.of()));
        List<GraphEdge> before = List.copyOf(graph.edges());

        new SpringModelEnricher().enrich(graph);

        assertTrue(graph.edges().containsAll(before), "deterministic evidence must survive enrichment untouched");
    }

    private static GraphNode node(String id, EntityKind kind, Map<String, String> attributes) {
        return new GraphNode(id, kind, id.substring(id.lastIndexOf(':') + 1), attributes, SOURCE);
    }

    private static GraphEdge edge(String from, String to, RelationKind kind, Map<String, String> attributes) {
        return new GraphEdge(from, to, kind, attributes, SOURCE);
    }
}

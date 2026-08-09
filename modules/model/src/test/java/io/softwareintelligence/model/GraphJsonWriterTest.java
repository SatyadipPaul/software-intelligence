package io.softwareintelligence.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphJsonWriterTest {
    private static final Provenance SOURCE = Provenance.syntax("Demo.java", 3, 1);

    @Test void escapes_every_control_character_a_java_literal_can_carry() {
        assertEquals("tab\\there", Json.quote("tab\there"));
        assertEquals("line\\nbreak", Json.quote("line\nbreak"));
        assertEquals("\\u0000", Json.quote(String.valueOf((char) 0)));
        assertEquals("\\u001f", Json.quote(String.valueOf((char) 0x1f)));
        assertEquals("quote\\\" backslash\\\\", Json.quote("quote\" backslash\\"));
        assertEquals("plain", Json.quote("plain"));
    }

    @Test void a_tabbed_annotation_value_still_produces_parseable_json() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(new GraphNode("type:demo.A", EntityKind.TYPE, "demo.A", Map.of("path", "/a\tb"), SOURCE));

        String json = GraphJsonWriter.write(graph);

        assertTrue(json.contains("\"path\":\"/a\\tb\""), json);
        assertTrue(json.indexOf('\t') < 0, "a raw tab inside a JSON string is invalid");
    }

    @Test void attributes_serialize_in_a_stable_order() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(new GraphNode("type:demo.A", EntityKind.TYPE, "demo.A", Map.of("z", "1", "a", "2", "m", "3"), SOURCE));

        assertTrue(GraphJsonWriter.write(graph).contains("{\"a\":\"2\",\"m\":\"3\",\"z\":\"1\"}"));
    }

    @Test void the_snapshot_declares_its_schema_version() {
        assertTrue(GraphJsonWriter.write(new CodeGraph()).contains("\"version\": \"" + GraphJsonWriter.SCHEMA_VERSION + "\""));
    }

    @Test void a_context_packet_writes_identity_and_resolver_evidence() {
        GraphNode subject = new GraphNode("type:demo.A", EntityKind.SERVICE, "demo.A", Map.of(), SOURCE);
        GraphEdge evidence = new GraphEdge("type:demo.B#call()", "type:demo.A#run()", RelationKind.CALLS, Map.of(),
                new Provenance("JDT_BINDING", 1.0, "B.java", 12, 5));
        ContextPacket packet = new ContextPacket(subject, List.of(), List.of(), List.of(), List.of(evidence));

        String json = GraphJsonWriter.writeContext(packet);

        assertTrue(json.contains("\"subject\": {\"id\":\"type:demo.A\""), json);
        assertTrue(json.contains("\"resolver\":\"JDT_BINDING\""), json);
        assertTrue(json.contains("\"line\":12"), json);
    }
}

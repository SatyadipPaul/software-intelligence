package io.softwareintelligence.queryengine;

import io.softwareintelligence.embedding.TextEncoder;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.Provenance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the index without the weights.
 *
 * <p>A stub encoder is not a weaker test here, it is a different one: these pin the behaviour this
 * class is responsible for — which nodes get a vector, what text they are embedded as, and that
 * results come back in similarity order — none of which depend on a real model, and all of which
 * would otherwise only be checked by a benchmark that needs a 90 MB download.
 */
class DenseIndexTest {

    /** Scores by how many query words appear in the text, so the expected order is readable by eye. */
    private static final class WordOverlapEncoder implements TextEncoder {
        @Override public int dimensions() { return 8; }

        @Override public float[][] encode(List<String> texts) {
            float[][] vectors = new float[texts.size()][];
            for (int i = 0; i < texts.size(); i++) vectors[i] = vector(texts.get(i));
            return vectors;
        }

        private static float[] vector(String text) {
            float[] v = new float[8];
            for (String word : text.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")) {
                if (!word.isBlank()) v[Math.floorMod(word.hashCode(), 8)] += 1;
            }
            double norm = 0;
            for (float component : v) norm += component * component;
            norm = Math.sqrt(norm);
            if (norm > 0) for (int i = 0; i < v.length; i++) v[i] /= (float) norm;
            return v;
        }

        @Override public void close() { }
    }

    private static CodeGraph graph() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(new GraphNode("type:demo.Disabled", EntityKind.TYPE, "demo.Disabled",
                Map.of("doc", "Signals that the annotated test is currently switched off"),
                Provenance.syntax("D.java", 1, 1)));
        graph.upsertNode(new GraphNode("type:demo.Ledger", EntityKind.TYPE, "demo.Ledger",
                Map.of("doc", "Records money movements"), Provenance.syntax("L.java", 1, 1)));
        graph.upsertNode(new GraphNode("type:demo.Disabled#value()", EntityKind.METHOD, "value",
                Map.of(), Provenance.syntax("D.java", 3, 1)));
        graph.upsertNode(new GraphNode("file:D.java", EntityKind.FILE, "D.java",
                Map.of(), Provenance.syntax("D.java", 1, 1)));
        return graph;
    }

    @Test void only_anchorable_nodes_are_embedded() {
        try (TextEncoder encoder = new WordOverlapEncoder()) {
            DenseIndex index = DenseIndex.over(graph(), encoder);
            // Two types. Methods and files are reached through the type that declares them, so
            // giving them vectors would cost index time to rank candidates no answer needs.
            assertEquals(2, index.size());
        }
    }

    @Test void ranks_by_similarity_and_returns_at_most_the_limit() {
        try (TextEncoder encoder = new WordOverlapEncoder()) {
            DenseIndex index = DenseIndex.over(graph(), encoder);
            List<DenseIndex.Hit> hits = index.search("switched off annotated test", 2);
            assertEquals(2, hits.size());
            assertEquals("type:demo.Disabled", hits.get(0).node().id());
            assertTrue(hits.get(0).similarity() >= hits.get(1).similarity(), "hits come back ordered");
        }
    }

    @Test void an_empty_limit_returns_nothing_rather_than_everything() {
        try (TextEncoder encoder = new WordOverlapEncoder()) {
            assertTrue(DenseIndex.over(graph(), encoder).search("anything", 0).isEmpty());
        }
    }

    @Test void a_dense_mode_without_an_encoder_says_so_rather_than_failing_obscurely() {
        CodeGraph graph = graph();
        Bm25Index flat = Bm25Index.over(graph);
        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> QueryPlanner.plan(graph, flat, null, null, "anything", 5, RetrievalMode.DENSE, 5, 4));
        assertTrue(failure.getMessage().contains("--embedding-model"), failure.getMessage());
        assertFalse(RetrievalMode.BM25.needsDense(), "a lexical mode must never require a model");
    }
}

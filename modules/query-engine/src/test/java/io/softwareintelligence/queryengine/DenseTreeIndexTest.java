package io.softwareintelligence.queryengine;

import io.softwareintelligence.embedding.TextEncoder;
import io.softwareintelligence.indextree.IndexKind;
import io.softwareintelligence.indextree.IndexNode;
import io.softwareintelligence.indextree.IndexTree;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how a branch is folded out of the cards beneath it.
 *
 * <p>The encoder here is a fixed table rather than a model, because what these tests are responsible
 * for is arithmetic: which cards a branch stands for, how they are weighted, and that the three
 * aggregators differ in the way their names claim. Whether the folded vector then ranks anything
 * usefully is a benchmark's question, not a unit test's — asserting semantic order over invented
 * numbers only tests the invented numbers.
 */
class DenseTreeIndexTest {

    /** Hands back a pinned vector per card name, so every expected value can be worked out by hand. */
    private record TableEncoder(Map<String, float[]> table) implements TextEncoder {
        @Override public int dimensions() { return 3; }

        @Override public float[][] encode(List<String> texts) {
            float[][] vectors = new float[texts.size()][];
            for (int i = 0; i < texts.size(); i++) {
                vectors[i] = table.getOrDefault(texts.get(i).trim(), new float[3]).clone();
            }
            return vectors;
        }

        @Override public void close() { }
    }

    private static IndexNode node(String id, IndexKind kind, String name, String... children) {
        return new IndexNode(id, kind, name, List.of(), List.of(children), Map.of());
    }

    /**
     * Root over two packages of deliberately different size: "big" holds three cards, "small" one.
     * That asymmetry is the whole point — it is what separates a size-weighted mean from a centroid.
     */
    private static IndexTree tree() {
        return new IndexTree("fixture", List.of(
                node(IndexTree.ROOT_ID, IndexKind.ROOT, "root", "big", "small"),
                node("big", IndexKind.PACKAGE, "big", "b1", "b2", "b3"),
                node("b1", IndexKind.TYPE, "b1"),
                node("b2", IndexKind.TYPE, "b2"),
                node("b3", IndexKind.TYPE, "b3"),
                node("small", IndexKind.PACKAGE, "small", "s1"),
                node("s1", IndexKind.TYPE, "s1")));
    }

    /** Every branch card is the zero vector, so a branch is exactly what its leaves make it. */
    private static TextEncoder encoder() {
        return new TableEncoder(Map.ofEntries(
                Map.entry("root", new float[] {0, 0, 0}),
                Map.entry("big", new float[] {0, 0, 0}),
                Map.entry("small", new float[] {0, 0, 0}),
                Map.entry("b1", new float[] {1, 0, 0}),
                Map.entry("b2", new float[] {1, 0, 0}),
                Map.entry("b3", new float[] {1, 0, 0}),
                Map.entry("s1", new float[] {0, 1, 0})));
    }

    private static IndexNode find(IndexTree tree, String id) {
        return tree.node(id).orElseThrow();
    }

    @Test void a_branch_is_scored_as_everything_beneath_it_not_as_its_own_card() {
        try (TextEncoder encoder = encoder()) {
            DenseTreeIndex index = DenseTreeIndex.over(tree(), encoder, DenseTreeIndex.Aggregation.MEAN);
            // "big"'s own card is the zero vector; its score comes entirely from its leaves.
            assertEquals(1.0, index.score(new float[] {1, 0, 0}, find(tree(), "big")), 1e-6);
            assertEquals(0.0, index.score(new float[] {0, 1, 0}, find(tree(), "big")), 1e-6);
        }
    }

    @Test void mean_weights_a_branch_by_how_many_cards_it_stands_for() {
        try (TextEncoder encoder = encoder()) {
            DenseTreeIndex index = DenseTreeIndex.over(tree(), encoder, DenseTreeIndex.Aggregation.MEAN);
            IndexNode root = find(tree(), IndexTree.ROOT_ID);
            // Three "big" cards against one "small" card, so the root leans three to one before
            // normalisation: cos to (1,0,0) must beat cos to (0,1,0) in that ratio.
            double towardsBig = index.score(new float[] {1, 0, 0}, root);
            double towardsSmall = index.score(new float[] {0, 1, 0}, root);
            assertEquals(3.0, towardsBig / towardsSmall, 1e-5);
        }
    }

    @Test void centroid_gives_every_child_the_same_say_whatever_its_size() {
        try (TextEncoder encoder = encoder()) {
            DenseTreeIndex index = DenseTreeIndex.over(tree(), encoder, DenseTreeIndex.Aggregation.CENTROID);
            IndexNode root = find(tree(), IndexTree.ROOT_ID);
            // The one card under "small" now counts as much as the three under "big".
            assertEquals(index.score(new float[] {0, 1, 0}, root),
                    index.score(new float[] {1, 0, 0}, root), 1e-6);
        }
    }

    @Test void max_keeps_the_strongest_value_on_each_dimension_rather_than_averaging_them() {
        try (TextEncoder encoder = encoder()) {
            DenseTreeIndex index = DenseTreeIndex.over(tree(), encoder, DenseTreeIndex.Aggregation.MAX);
            IndexNode root = find(tree(), IndexTree.ROOT_ID);
            // Both dimensions reach 1 before normalisation, so the root ends up equidistant — and
            // that saturation is why MAX loses discrimination on a wide tree.
            assertEquals(index.score(new float[] {0, 1, 0}, root),
                    index.score(new float[] {1, 0, 0}, root), 1e-6);
            assertEquals(Math.sqrt(0.5), index.score(new float[] {1, 0, 0}, root), 1e-6);
        }
    }

    @Test void a_leaf_is_its_own_card_under_every_aggregation() {
        for (DenseTreeIndex.Aggregation how : DenseTreeIndex.Aggregation.values()) {
            try (TextEncoder encoder = encoder()) {
                DenseTreeIndex index = DenseTreeIndex.over(tree(), encoder, how);
                assertEquals(1.0, index.score(new float[] {0, 1, 0}, find(tree(), "s1")), 1e-6, how.name());
            }
        }
    }

    @Test void every_node_is_folded_and_the_aggregation_is_reported() {
        try (TextEncoder encoder = encoder()) {
            DenseTreeIndex index = DenseTreeIndex.over(tree(), encoder, DenseTreeIndex.Aggregation.MAX);
            assertEquals(7, index.size());
            assertEquals(3, index.dimensions());
            assertEquals(DenseTreeIndex.Aggregation.MAX, index.aggregation());
        }
    }

    @Test void a_node_outside_the_tree_scores_zero_rather_than_throwing() {
        try (TextEncoder encoder = encoder()) {
            DenseTreeIndex index = DenseTreeIndex.over(tree(), encoder, DenseTreeIndex.Aggregation.MEAN);
            IndexNode stranger = node("absent", IndexKind.TYPE, "absent");
            assertEquals(0.0, index.score(new float[] {1, 0, 0}, stranger));
        }
    }

    @Test void folded_vectors_are_unit_length_so_a_score_is_a_cosine() {
        try (TextEncoder encoder = encoder()) {
            DenseTreeIndex index = DenseTreeIndex.over(tree(), encoder, DenseTreeIndex.Aggregation.MEAN);
            IndexTree tree = tree();
            for (String id : List.of(IndexTree.ROOT_ID, "big", "small", "s1")) {
                double self = index.score(unit(index, tree, id), find(tree, id));
                assertTrue(Math.abs(self - 1.0) < 1e-5, id + " scored " + self + " against itself");
            }
        }
    }

    /** Recovers a node's stored vector by scoring it against each basis direction. */
    private static float[] unit(DenseTreeIndex index, IndexTree tree, String id) {
        float[] recovered = new float[3];
        for (int d = 0; d < 3; d++) {
            float[] basis = new float[3];
            basis[d] = 1;
            recovered[d] = (float) index.score(basis, find(tree, id));
        }
        return recovered;
    }
}

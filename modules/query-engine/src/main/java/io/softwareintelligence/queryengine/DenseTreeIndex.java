package io.softwareintelligence.queryengine;

import io.softwareintelligence.embedding.TextEncoder;
import io.softwareintelligence.model.Attributes;
import io.softwareintelligence.indextree.IndexKind;
import io.softwareintelligence.indextree.IndexNode;
import io.softwareintelligence.indextree.IndexTree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A vector for every branch of the index tree, formed from everything beneath it.
 *
 * <p>A branch card on its own is close to contentless — a module is one word, a package is a
 * dotted path — so embedding it and comparing that to a question measures almost nothing. The
 * lexical scorer never had this problem because it scores a node as its <em>whole subtree</em>, and
 * a dense chooser that skips that step is not a fairer test of meaning, it is a worse test of
 * nothing. Measured: embedding branch cards alone scored 0.036 on junit5 against the lexical
 * chooser's 0.273.
 *
 * <p>So a branch is represented by the mean of the vectors of every card below it. That is
 * aggregation of <em>representations</em> rather than of scores, which is the distinction
 * {@code docs/benchmarks/subtree-dilution-2026-09-16.md} recorded after max-over-scores came out
 * worse than either of its inputs.
 *
 * <p>Aggregates are computed once per tree by a single post-order pass: a node's vector is its own
 * card combined with its children's, so the whole tree costs one traversal rather than one range
 * scan per query.
 *
 * <p>Three ways to combine, which is PARADE's comparison plus the one it does not cover.
 * {@code MEAN} asks what a branch is about on average, every card below it counting once.
 * {@code MAX} asks, dimension by dimension, what the most any card below it has to say on that
 * dimension — the representation-level form of "is the answer somewhere under here".
 * {@code CENTROID} is the direct test of the dilution hypothesis: each child contributes one unit
 * vector regardless of how many cards are under it, so a package of five is not drowned by a
 * sibling of five hundred. Summing is not a fourth option: cosine ignores length, so a sum and a
 * mean rank identically, and measuring both would be theatre.
 *
 * <p>Measured, {@code docs/benchmarks/subtree-aggregation-2026-09-17.md}: MEAN is the default
 * because it is the only one that holds up on a deep tree. MAX collapses — an element-wise maximum
 * over thousands of unit vectors saturates, and every large branch ends up looking alike. CENTROID
 * beats it on a shallow repository and loses badly on a deep one, which says the dilution the
 * weighting was suspected of causing is the lesser of the two distortions available.
 */
public final class DenseTreeIndex {

    /** How a branch combines the cards beneath it. */
    public enum Aggregation {
        /** Element-wise mean: what the subtree is about on average. */
        MEAN,
        /** Element-wise maximum: the strongest thing any card below says on each dimension. */
        MAX,
        /** Mean of the children's directions: every child counts once, whatever its size. */
        CENTROID
    }

    private final Map<String, Integer> position = new HashMap<>();
    private final float[][] aggregates;
    private final int dimensions;
    private final Aggregation aggregation;

    private DenseTreeIndex(Map<String, Integer> position, float[][] aggregates,
                           int dimensions, Aggregation aggregation) {
        this.position.putAll(position);
        this.aggregates = aggregates;
        this.dimensions = dimensions;
        this.aggregation = aggregation;
    }

    public int dimensions() { return dimensions; }

    public Aggregation aggregation() { return aggregation; }

    public int size() { return aggregates.length; }

    /** Embeds every card once, then folds each subtree into one vector. */
    public static DenseTreeIndex over(IndexTree tree, TextEncoder encoder, Aggregation aggregation) {
        List<IndexNode> preorder = new ArrayList<>();
        Map<String, Integer> position = new HashMap<>();
        number(tree, preorder, position);

        // Each card may carry more than one source of prose; they are encoded in one flat list and
        // pooled per node, so a card's vector means the same thing here as it does in DenseIndex.
        List<String> texts = new ArrayList<>();
        int[] spans = new int[preorder.size() + 1];
        for (int i = 0; i < preorder.size(); i++) {
            spans[i] = texts.size();
            texts.addAll(readable(preorder.get(i)));
        }
        spans[preorder.size()] = texts.size();
        float[][] encoded = encoder.encode(texts);
        float[][] cards = new float[preorder.size()][];
        for (int i = 0; i < preorder.size(); i++) cards[i] = Vectors.pool(encoded, spans[i], spans[i + 1]);
        int dimensions = encoder.dimensions();

        // Post-order by walking preorder backwards: a node always appears before its descendants,
        // so going right to left means every child is folded before its parent is.
        float[][] aggregates = new float[preorder.size()][];
        int[] counts = new int[preorder.size()];
        for (int i = preorder.size() - 1; i >= 0; i--) {
            float[] folded = cards[i].clone();
            int count = 1;
            for (IndexNode child : tree.children(preorder.get(i))) {
                Integer at = position.get(child.id());
                if (at == null || aggregates[at] == null) continue;
                // CENTROID weights every child as one, so a child is folded in as a direction only;
                // MEAN weights it by the cards it stands for, so the result is a true subtree mean.
                int weight = aggregation == Aggregation.CENTROID ? 1 : counts[at];
                combine(folded, aggregates[at], weight, count, aggregation);
                count += weight;
            }
            // A CENTROID parent must see its children as unit vectors, so each node is normalised as
            // soon as it is folded rather than in one pass at the end.
            if (aggregation == Aggregation.CENTROID) Vectors.normalize(folded);
            aggregates[i] = folded;
            counts[i] = count;
        }
        for (float[] vector : aggregates) Vectors.normalize(vector);
        return new DenseTreeIndex(position, aggregates, dimensions, aggregation);
    }

    /**
     * Folds {@code other} into {@code into}, each side weighted by how much it already stands for,
     * so the result is a true mean rather than the parent's own card counting as much as everything
     * below it.
     */
    private static void combine(float[] into, float[] other, int otherCount, int intoCount, Aggregation aggregation) {
        if (aggregation == Aggregation.MAX) {
            for (int d = 0; d < into.length; d++) into[d] = Math.max(into[d], other[d]);
            return;
        }
        double total = intoCount + otherCount;
        for (int d = 0; d < into.length; d++) {
            into[d] = (float) ((into[d] * intoCount + other[d] * otherCount) / total);
        }
    }

    /** Cosine of the question against the folded vector of everything at or below this node. */
    public double score(float[] query, IndexNode node) {
        Integer at = position.get(node.id());
        if (at == null) return 0.0;
        float[] vector = aggregates[at];
        double dot = 0.0;
        for (int d = 0; d < dimensions; d++) dot += (double) vector[d] * query[d];
        return dot;
    }

    /** Numbers every node in preorder, iteratively, so a deep tree cannot overflow the stack. */
    private static void number(IndexTree tree, List<IndexNode> preorder, Map<String, Integer> position) {
        Deque<IndexNode> stack = new ArrayDeque<>();
        stack.push(tree.root());
        while (!stack.isEmpty()) {
            IndexNode node = stack.pop();
            if (position.containsKey(node.id())) continue;
            position.put(node.id(), preorder.size());
            preorder.add(node);
            List<IndexNode> children = tree.children(node);
            for (int i = children.size() - 1; i >= 0; i--) stack.push(children.get(i));
        }
    }

    /** The card as prose: what the entry is, then each optional source of added prose in turn. */
    static List<String> readable(IndexNode node) {
        String name = node.name();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1 && node.kind() != IndexKind.PACKAGE) {
            name = name.substring(dot + 1);
        }
        String words = name.replaceAll("[._/]", " ")
                .replaceAll("(?<!^)(?=[A-Z][a-z])|(?<=[a-z0-9])(?=[A-Z])", " ");
        StringBuilder identity = new StringBuilder(words);
        String documentation = node.facts().getOrDefault(Attributes.DOC, node.facts().getOrDefault("summary", ""));
        if (!documentation.isBlank()) identity.append(". ").append(documentation);

        List<String> facets = new ArrayList<>();
        facets.add(identity.toString());
        String behaviour = node.facts().getOrDefault(Attributes.BEHAVIOUR, "");
        if (!behaviour.isBlank()) facets.add(behaviour);
        String history = node.facts().getOrDefault(Attributes.HISTORY, "");
        if (!history.isBlank()) facets.add(history);
        return facets;
    }
}

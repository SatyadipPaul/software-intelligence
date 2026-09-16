package io.softwareintelligence.queryengine;

import io.softwareintelligence.embedding.TextEncoder;
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
 * <p>Subtree sums are a subtraction. Nodes are numbered in preorder so a subtree is a contiguous
 * range, and prefix sums over the vectors turn "everything below this node" into one vector
 * difference — linear in tree × dimensions, once per tree.
 */
public final class DenseTreeIndex {

    private final Map<String, Integer> position = new HashMap<>();
    private final Map<String, Integer> subtreeEnd = new HashMap<>();
    private final float[][] prefix;
    private final int dimensions;

    private DenseTreeIndex(float[][] prefix, int dimensions) {
        this.prefix = prefix;
        this.dimensions = dimensions;
    }

    public int dimensions() { return dimensions; }

    /** Embeds every card once, then prefix-sums so any subtree's mean is one subtraction. */
    public static DenseTreeIndex over(IndexTree tree, TextEncoder encoder) {
        List<IndexNode> preorder = new ArrayList<>();
        DenseTreeIndex index = new DenseTreeIndex(null, encoder.dimensions());
        index.number(tree, preorder);

        List<String> texts = preorder.stream().map(DenseTreeIndex::readable).toList();
        float[][] vectors = encoder.encode(texts);
        int dimensions = encoder.dimensions();
        float[][] prefix = new float[preorder.size() + 1][dimensions];
        for (int i = 0; i < preorder.size(); i++) {
            for (int d = 0; d < dimensions; d++) prefix[i + 1][d] = prefix[i][d] + vectors[i][d];
        }
        DenseTreeIndex built = new DenseTreeIndex(prefix, dimensions);
        built.position.putAll(index.position);
        built.subtreeEnd.putAll(index.subtreeEnd);
        return built;
    }

    /** Cosine of the question against the mean of everything at or below this node. */
    public double score(float[] query, IndexNode node) {
        Integer start = position.get(node.id());
        if (start == null || prefix == null) return 0.0;
        int end = subtreeEnd.getOrDefault(node.id(), start + 1);
        int count = end - start;
        if (count <= 0) return 0.0;
        double dot = 0.0;
        double norm = 0.0;
        for (int d = 0; d < dimensions; d++) {
            double mean = (prefix[end][d] - prefix[start][d]) / count;
            dot += mean * query[d];
            norm += mean * mean;
        }
        norm = Math.sqrt(norm);
        return norm == 0 ? 0.0 : dot / norm;
    }

    /** Numbers every node in preorder, iteratively, so a deep tree cannot overflow the stack. */
    private void number(IndexTree tree, List<IndexNode> preorder) {
        record Frame(IndexNode node, boolean entered) { }
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(tree.root(), false));
        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            if (frame.entered()) {
                subtreeEnd.put(frame.node().id(), preorder.size());
                continue;
            }
            if (position.containsKey(frame.node().id())) continue;
            position.put(frame.node().id(), preorder.size());
            preorder.add(frame.node());
            stack.push(new Frame(frame.node(), true));
            List<IndexNode> children = tree.children(frame.node());
            for (int i = children.size() - 1; i >= 0; i--) stack.push(new Frame(children.get(i), false));
        }
    }

    /** The card as prose: split identifiers, then whatever documentation it carries. */
    static String readable(IndexNode node) {
        String name = node.name();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1 && node.kind() != IndexKind.PACKAGE) {
            name = name.substring(dot + 1);
        }
        String words = name.replaceAll("[._/]", " ")
                .replaceAll("(?<!^)(?=[A-Z][a-z])|(?<=[a-z0-9])(?=[A-Z])", " ");
        String documentation = node.facts().getOrDefault("doc", node.facts().getOrDefault("summary", ""));
        return documentation.isBlank() ? words : words + ". " + documentation;
    }
}

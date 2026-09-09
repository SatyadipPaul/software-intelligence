package io.softwareintelligence.queryengine;

import io.softwareintelligence.indextree.IndexCards;
import io.softwareintelligence.indextree.IndexNode;
import io.softwareintelligence.indextree.IndexTree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * BM25 over what a branch <em>contains</em>, not only over what its card prints.
 *
 * <p>Scoring a branch by its own card was the first thing tried, and it fails at scale for a reason
 * worth recording: a card shows a handful of exemplars, so on jackson-databind the term
 * {@code ObjectMapper} appears on none of the 83 module cards, including the one module that
 * contains it. A descent then chooses between branches that all score zero, and tree retrieval
 * measured 0.400 against flat retrieval's 1.000 — a regression, on exactly the repository the
 * design expected it to win.
 *
 * <p>So a node is scored as the document formed by its whole subtree: term frequency is how often
 * the term appears in the cards below it, and document length is the size of everything below it.
 * BM25's length normalization then does the discriminating — a small package holding the match
 * beats a large module holding the same match and a thousand other things.
 *
 * <p>The subtree is not materialized. Nodes are numbered in preorder, so a subtree is a contiguous
 * range, and each term's postings are a sorted array of those numbers; a subtree's term frequency is
 * two binary searches. That keeps the index linear in the tree rather than in tree × depth.
 */
final class CardIndex {
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    /** Positions of each node in preorder, and the end of its subtree. */
    private final Map<String, Integer> position = new HashMap<>();
    private final Map<String, Integer> subtreeEnd = new HashMap<>();

    /** For each term, the preorder positions carrying it and the count at each. */
    private final Map<String, int[]> postings = new HashMap<>();
    private final Map<String, int[]> counts = new HashMap<>();

    /**
     * For each simple name, the positions of the nodes called exactly that.
     *
     * <p>Kept apart from the term postings because it answers a different and much stronger
     * question: not "does this branch mention the words" but "is the thing being asked for
     * somewhere underneath". Term frequency cannot express that — on jackson-databind the package
     * {@code node} mentions "json" and "node" hundreds of times and does not contain
     * {@code JsonNode}, while the package that does contain it mentions them twice.
     */
    private final Map<String, int[]> namePostings = new HashMap<>();

    /** Prefix sums of own term counts, so a subtree's length is one subtraction. */
    private int[] lengthPrefix = new int[1];
    private int documents;
    private double averageLength = 1.0;

    static CardIndex over(IndexTree tree) {
        CardIndex index = new CardIndex();
        List<IndexNode> preorder = new ArrayList<>();
        index.number(tree, preorder);

        Map<String, List<int[]>> gathered = new TreeMap<>();
        int[] ownLength = new int[preorder.size()];
        for (int i = 0; i < preorder.size(); i++) {
            Map<String, Integer> terms = new TreeMap<>();
            for (String term : Bm25Index.tokenize(IndexCards.searchText(preorder.get(i)))) terms.merge(term, 1, Integer::sum);
            for (Map.Entry<String, Integer> entry : terms.entrySet()) {
                gathered.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(new int[]{i, entry.getValue()});
                ownLength[i] += entry.getValue();
            }
        }
        Map<String, List<Integer>> names = new TreeMap<>();
        for (int i = 0; i < preorder.size(); i++) {
            names.computeIfAbsent(simpleName(preorder.get(i).name()), ignored -> new ArrayList<>()).add(i);
        }
        names.forEach((name, positions) -> index.namePostings.put(name, positions.stream().mapToInt(Integer::intValue).toArray()));

        gathered.forEach((term, entries) -> {
            int[] positions = new int[entries.size()];
            int[] frequencies = new int[entries.size()];
            for (int i = 0; i < entries.size(); i++) {
                positions[i] = entries.get(i)[0];
                frequencies[i] = entries.get(i)[1];
            }
            index.postings.put(term, positions);
            index.counts.put(term, frequencies);
        });

        index.lengthPrefix = new int[ownLength.length + 1];
        for (int i = 0; i < ownLength.length; i++) index.lengthPrefix[i + 1] = index.lengthPrefix[i] + ownLength[i];
        index.documents = Math.max(1, preorder.size());
        long total = 0;
        for (IndexNode node : preorder) total += index.length(node);
        index.averageLength = Math.max(1.0, (double) total / index.documents);
        return index;
    }

    /** Numbers every node in preorder, iteratively: a deep tree must not overflow the stack. */
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

    /**
     * BM25 for the question against everything at or below this node.
     *
     * <p>A node the tree does not hold scores zero rather than throwing: a caller may legitimately
     * ask about a node from a differently shaped tree, and a silent zero is the honest answer.
     */
    double score(List<String> queryTerms, IndexNode node) {
        Integer start = position.get(node.id());
        if (start == null) return 0.0;
        int end = subtreeEnd.getOrDefault(node.id(), start + 1);
        int length = length(node);
        if (length == 0) return 0.0;
        double score = 0.0;
        for (String term : queryTerms) {
            int[] positions = postings.get(term);
            if (positions == null) continue;
            int frequency = frequencyIn(term, positions, start, end);
            if (frequency == 0) continue;
            int containing = positions.length;
            double idf = Math.log(1 + (documents - containing + 0.5) / (containing + 0.5));
            score += idf * (frequency * (K1 + 1)) / (frequency + K1 * (1 - B + B * length / averageLength));
        }
        return score;
    }

    /**
     * Whether anything at or below this node is called exactly {@code name}.
     *
     * <p>This is what a reader uses. Looking for {@code ObjectMapper} in a table of contents, you
     * open the section that contains something by that name — you do not weigh how often the
     * surrounding sections say "object" and "mapper".
     */
    boolean subtreeHolds(IndexNode node, String name) {
        if (name == null || name.isBlank()) return false;
        int[] positions = namePostings.get(name.toLowerCase(java.util.Locale.ROOT));
        if (positions == null) return false;
        Integer start = position.get(node.id());
        if (start == null) return false;
        int end = subtreeEnd.getOrDefault(node.id(), start + 1);
        int at = lowerBound(positions, start);
        return at < positions.length && positions[at] < end;
    }

    /** The last dotted segment, which is how a question names a Java symbol. */
    private static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(dot + 1)).toLowerCase(java.util.Locale.ROOT);
    }

    /** How much text this node's subtree holds, as a document length. */
    private int length(IndexNode node) {
        Integer start = position.get(node.id());
        if (start == null) return 0;
        int end = subtreeEnd.getOrDefault(node.id(), start + 1);
        return lengthPrefix[end] - lengthPrefix[start];
    }

    private int frequencyIn(String term, int[] positions, int start, int end) {
        int[] frequencies = counts.get(term);
        int total = 0;
        for (int i = lowerBound(positions, start); i < positions.length && positions[i] < end; i++) {
            total += frequencies[i];
        }
        return total;
    }

    private static int lowerBound(int[] values, int target) {
        int low = 0;
        int high = values.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (values[middle] < target) low = middle + 1;
            else high = middle;
        }
        return low;
    }
}

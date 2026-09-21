package io.softwareintelligence.evaluation;

import io.softwareintelligence.indextree.IndexNode;
import io.softwareintelligence.indextree.IndexTree;
import io.softwareintelligence.queryengine.TreeNavigator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A chooser that already knows the answer, used to measure the descent's ceiling.
 *
 * <p>Not a product path and not a cheat: it exists to separate two failures that every previous
 * number confused. When a descent misses, either the chooser picked the wrong branch, or the answer
 * was not reachable by descending at all — wrong tree shape, too narrow a beam, too shallow a cap,
 * an anchoring rule that stops early. An oracle cannot make the first mistake, so whatever it still
 * misses is the second, and that is the ceiling no chooser however clever can pass.
 *
 * <p>A benchmark that reports only this number would be meaningless. A benchmark that never reports
 * it cannot tell you whether to invest in a better chooser.
 */
public final class OracleChooser implements TreeNavigator.Chooser {

    private final Set<String> onThePath;

    private OracleChooser(Set<String> onThePath) {
        this.onThePath = onThePath;
    }

    /**
     * Marks every index node whose subtree covers one of {@code targets}, so choosing is a lookup.
     *
     * <p>Computed by walking up from the covering nodes rather than down from the root: a subtree
     * containing a target is exactly an ancestor of a node that covers it.
     */
    public static OracleChooser forTargets(IndexTree tree, Set<String> targets) {
        Set<String> path = new HashSet<>();
        Deque<List<IndexNode>> stack = new ArrayDeque<>();
        stack.push(List.of(tree.root()));
        mark(tree, tree.root(), targets, path, new ArrayList<>());
        return new OracleChooser(path);
    }

    /** Depth-first, recording the ancestry of every node that covers a target. */
    private static boolean mark(IndexTree tree, IndexNode node, Set<String> targets,
                                Set<String> path, List<String> ancestry) {
        boolean covers = node.graphIds().stream().anyMatch(targets::contains);
        ancestry.add(node.id());
        for (IndexNode child : tree.children(node)) {
            covers |= mark(tree, child, targets, path, ancestry);
        }
        ancestry.remove(ancestry.size() - 1);
        if (covers) path.add(node.id());
        return covers;
    }

    @Override
    public List<String> choose(IndexNode parent, List<TreeNavigator.Scored> ranked, int keep) {
        List<String> correct = ranked.stream()
                .map(scored -> scored.node().id())
                .filter(onThePath::contains)
                .limit(Math.max(1, keep))
                .toList();
        // When no child leads to the answer, the descent is already lost and the oracle has nothing
        // to add: fall back to the ranking so the run still terminates the way a real one would.
        if (!correct.isEmpty()) return correct;
        return ranked.stream().limit(Math.max(1, keep)).map(scored -> scored.node().id()).toList();
    }
}

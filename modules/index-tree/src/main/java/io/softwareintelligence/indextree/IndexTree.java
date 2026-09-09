package io.softwareintelligence.indextree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A navigable table of contents over a {@link io.softwareintelligence.model.CodeGraph}.
 *
 * <p>The tree is derived, never authored: {@link IndexTreeBuilder} reads containment the graph
 * already proved and arranges it into levels. It adds no relationship, so it cannot be wrong in a
 * way the graph is not already wrong.
 *
 * <p>A tree is pinned to the graph it came from by {@code fingerprint}. A tree read back against a
 * different graph is refused rather than silently navigated, because anchors from a stale tree
 * point at symbols that may no longer exist.
 */
public final class IndexTree {
    /** Bumped when the node shape or the derivation rules change. */
    public static final String SCHEMA_VERSION = "0.1";

    public static final String ROOT_ID = "index:root";

    private final String fingerprint;
    private final Map<String, IndexNode> byId;
    private final List<IndexNode> ordered;

    public IndexTree(String fingerprint, List<IndexNode> nodes) {
        this.fingerprint = fingerprint;
        Map<String, IndexNode> index = new LinkedHashMap<>();
        for (IndexNode node : nodes) index.put(node.id(), node);
        this.byId = Collections.unmodifiableMap(index);
        this.ordered = List.copyOf(nodes);
    }

    public String fingerprint() { return fingerprint; }

    public List<IndexNode> nodes() { return ordered; }

    public int size() { return ordered.size(); }

    public Optional<IndexNode> node(String id) { return Optional.ofNullable(byId.get(id)); }

    public IndexNode root() {
        return node(ROOT_ID).orElseThrow(() -> new IllegalStateException("index tree has no root"));
    }

    /** The children of a node, in presentation order, skipping any id the tree does not hold. */
    public List<IndexNode> children(IndexNode parent) {
        List<IndexNode> children = new ArrayList<>();
        for (String child : parent.children()) node(child).ifPresent(children::add);
        return List.copyOf(children);
    }

    /** The depth of the deepest node, counting the root as depth 0. */
    public int depth() {
        return depth(root(), 0, 0);
    }

    private int depth(IndexNode node, int current, int guard) {
        if (guard > 64) return current;
        int deepest = current;
        for (IndexNode child : children(node)) deepest = Math.max(deepest, depth(child, current + 1, guard + 1));
        return deepest;
    }
}

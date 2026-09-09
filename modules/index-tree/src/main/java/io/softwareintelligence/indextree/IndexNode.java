package io.softwareintelligence.indextree;

import java.util.List;
import java.util.Map;

/**
 * One entry in the table of contents.
 *
 * <p>A node is a <em>pointer set</em>, never a claim: {@code graphIds} names the graph nodes it
 * stands for, and everything in {@code facts} is counted from those nodes rather than described.
 * The one exception is the {@code summary} fact, which is copied from a pinned enrichment claim
 * when the graph carries one, and is the only text here that a model may have authored.
 *
 * @param id       tree identity, always prefixed {@code index:} so it can never collide with a graph id
 * @param kind     what this node stands for
 * @param name     display name, taken from the graph node or derived for synthetic nodes
 * @param graphIds the graph nodes this entry covers, sorted; empty for {@code ROOT} and {@code GROUP}
 * @param children child tree ids, in the order they should be presented
 * @param facts    counted, sorted facts rendered onto the node's card
 */
public record IndexNode(String id, IndexKind kind, String name, List<String> graphIds,
                        List<String> children, Map<String, String> facts) {

    /** The graph node an anchor on this entry resolves to, or empty for a synthetic node. */
    public String anchor() {
        return graphIds.isEmpty() ? "" : graphIds.get(0);
    }

    public boolean leaf() {
        return children.isEmpty();
    }
}

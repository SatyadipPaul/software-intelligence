package io.softwareintelligence.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Mutable only while building; exported graph data is immutable to consumers.
 *
 * <p>Node insertion, node lookup, and adjacency are index-backed so that building a graph is
 * linear in the number of nodes and edges rather than quadratic.
 */
public final class CodeGraph {
    private final List<GraphNode> nodes = new ArrayList<>();
    private final List<GraphEdge> edges = new ArrayList<>();
    private final Map<String, Integer> nodeIndex = new HashMap<>();
    private final Map<String, List<GraphEdge>> outgoing = new HashMap<>();
    private final Map<String, List<GraphEdge>> incoming = new HashMap<>();
    private final Set<String> edgeKeys = new HashSet<>();

    /** Adds a node or upgrades a provisional external node when source later proves its identity. */
    public void upsertNode(GraphNode node) { upsertNode(node, false); }

    /**
     * Adds a node, replacing any existing entry when {@code authoritative} — that is, when this
     * node comes from the symbol's own declaration rather than from a reference to it. A reference
     * never overwrites a declaration, so provenance always points at where a symbol is defined.
     */
    public void upsertNode(GraphNode node, boolean authoritative) {
        Integer existing = nodeIndex.get(node.id());
        if (existing == null) {
            nodeIndex.put(node.id(), nodes.size());
            nodes.add(node);
            return;
        }
        if (authoritative || (nodes.get(existing).kind() == EntityKind.EXTERNAL_SYMBOL && node.kind() != EntityKind.EXTERNAL_SYMBOL)) {
            nodes.set(existing, node);
        }
    }

    /**
     * Adds an edge unless the identical relationship was already recorded at the identical source
     * position. Two call sites on different lines stay distinct because each is separate evidence.
     */
    public void addEdge(GraphEdge edge) {
        if (!edgeKeys.add(edgeKey(edge))) return;
        edges.add(edge);
        outgoing.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
        incoming.computeIfAbsent(edge.to(), ignored -> new ArrayList<>()).add(edge);
    }

    public List<GraphNode> nodes() { return Collections.unmodifiableList(nodes); }
    public List<GraphEdge> edges() { return Collections.unmodifiableList(edges); }

    public Optional<GraphNode> node(String id) {
        Integer index = nodeIndex.get(id);
        return index == null ? Optional.empty() : Optional.of(nodes.get(index));
    }

    public List<GraphEdge> outgoing(String from) {
        return Collections.unmodifiableList(outgoing.getOrDefault(from, List.of()));
    }

    public List<GraphEdge> incoming(String to) {
        return Collections.unmodifiableList(incoming.getOrDefault(to, List.of()));
    }

    private static String edgeKey(GraphEdge edge) {
        Provenance provenance = edge.provenance();
        return edge.from() + '|' + edge.to() + '|' + edge.kind() + '|'
                + provenance.file() + '|' + provenance.line() + '|' + provenance.column();
    }
}

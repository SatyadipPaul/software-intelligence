package io.softwareintelligence.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Mutable only while building; exported graph data is immutable to consumers. */
public final class CodeGraph {
    private final List<GraphNode> nodes = new ArrayList<>();
    private final List<GraphEdge> edges = new ArrayList<>();

    public void addNode(GraphNode node) { nodes.add(node); }
    public void addEdge(GraphEdge edge) { edges.add(edge); }
    public List<GraphNode> nodes() { return Collections.unmodifiableList(nodes); }
    public List<GraphEdge> edges() { return Collections.unmodifiableList(edges); }
}


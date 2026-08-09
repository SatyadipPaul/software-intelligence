package io.softwareintelligence.model;

import java.util.List;

/** Compact, query-specific evidence packet for future reasoning clients. */
public record ContextPacket(GraphNode subject, List<GraphNode> callers, List<GraphNode> endpoints,
                            List<GraphNode> dependencies, List<GraphEdge> evidence) { }

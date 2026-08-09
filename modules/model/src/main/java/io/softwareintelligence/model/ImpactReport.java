package io.softwareintelligence.model;

import java.util.List;

/** A deterministic impact result. Each path is composed entirely of evidence-bearing graph edges. */
public record ImpactReport(GraphNode subject, List<ImpactPath> direct, List<ImpactPath> transitive) {
    public record ImpactPath(GraphNode target, List<GraphEdge> evidence) { }
}

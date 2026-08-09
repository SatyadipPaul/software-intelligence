package io.softwareintelligence.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Graph-only analyses: no LLM inference and no relationship without recorded evidence. */
public final class GraphQueries {
    private GraphQueries() { }

    public static Optional<GraphNode> findSymbol(CodeGraph graph, String query) {
        return graph.nodes().stream().filter(node -> node.id().equals(query) || node.name().equals(query) || node.id().endsWith(query)).findFirst();
    }

    /** Traverses incoming references: answers "what is affected if this symbol disappears?". */
    public static ImpactReport impact(CodeGraph graph, GraphNode subject, int maxDepth) {
        Map<String, GraphNode> nodes = new HashMap<>();
        Map<String, List<GraphEdge>> incoming = new HashMap<>();
        Map<String, List<GraphEdge>> outgoing = new HashMap<>();
        graph.nodes().forEach(node -> nodes.put(node.id(), node));
        graph.edges().forEach(edge -> {
            incoming.computeIfAbsent(edge.to(), ignored -> new ArrayList<>()).add(edge);
            outgoing.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
        });

        List<ImpactReport.ImpactPath> direct = new ArrayList<>();
        List<ImpactReport.ImpactPath> transitive = new ArrayList<>();
        ArrayDeque<PathState> queue = new ArrayDeque<>();
        Set<String> seeds = declaredMembers(subject.id(), outgoing);
        for (String seed : seeds) queue.add(new PathState(seed, List.of()));
        Set<String> visited = new HashSet<>(seeds);
        while (!queue.isEmpty()) {
            PathState current = queue.remove();
            if (current.edges().size() >= maxDepth) continue;
            for (GraphEdge edge : incoming.getOrDefault(current.nodeId(), List.of())) {
                if (!impactRelevant(edge.kind())) continue;
                if (!visited.add(edge.from())) continue;
                List<GraphEdge> path = new ArrayList<>(current.edges());
                path.add(edge);
                GraphNode source = nodes.get(edge.from());
                if (source == null) continue;
                ImpactReport.ImpactPath result = new ImpactReport.ImpactPath(source, List.copyOf(path));
                if (path.size() == 1) direct.add(result); else transitive.add(result);
                queue.add(new PathState(source.id(), path));
            }
        }
        return new ImpactReport(subject, List.copyOf(direct), List.copyOf(transitive));
    }

    public static Collection<GraphEdge> outgoing(CodeGraph graph, String from) {
        return graph.edges().stream().filter(edge -> edge.from().equals(from)).toList();
    }

    private record PathState(String nodeId, List<GraphEdge> edges) { }

    private static Set<String> declaredMembers(String root, Map<String, List<GraphEdge>> outgoing) {
        Set<String> members = new HashSet<>(Set.of(root));
        ArrayDeque<String> queue = new ArrayDeque<>(List.of(root));
        while (!queue.isEmpty()) {
            String current = queue.remove();
            for (GraphEdge edge : outgoing.getOrDefault(current, List.of())) {
                if (edge.kind() == RelationKind.DECLARES && members.add(edge.to())) queue.add(edge.to());
            }
        }
        return members;
    }

    private static boolean impactRelevant(RelationKind kind) {
        return switch (kind) {
            case CALLS, IMPORTS, EXTENDS, IMPLEMENTS, OVERRIDES, EXPOSES, PERSISTS, PUBLISHES, CONSUMES, CONFIGURES, DEPENDS_ON, PARTICIPATES_IN -> true;
            default -> false;
        };
    }
}

package io.softwareintelligence.architecture;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.RelationKind;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Type-level centrality. Degree centrality answers "how many things touch this"; PageRank answers
 * "how much of the repository ultimately depends on this", which ranks a low-degree type that
 * everything reaches transitively above a high-degree leaf.
 *
 * <p>PageRank runs a fixed iteration count with a fixed damping factor rather than converging on a
 * tolerance, so the same graph always yields the same scores.
 */
public final class Centrality {
    private static final double DAMPING = 0.85;
    private static final int ITERATIONS = 40;
    private static final Set<RelationKind> STRUCTURAL =
            Set.of(RelationKind.CALLS, RelationKind.DEPENDS_ON, RelationKind.EXTENDS, RelationKind.IMPLEMENTS, RelationKind.OVERRIDES);

    public record Score(String id, double pageRank, int inDegree, int outDegree) { }

    private Centrality() { }

    public static List<Score> rank(CodeGraph graph) {
        Map<String, List<String>> dependsOn = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, Integer> outDegree = new HashMap<>();
        for (GraphNode node : graph.nodes()) {
            if (Communities.isRepositoryType(graph, node)) dependsOn.putIfAbsent(node.id(), new java.util.ArrayList<>());
        }
        for (GraphEdge edge : graph.edges()) {
            if (!STRUCTURAL.contains(edge.kind())) continue;
            String from = Communities.owningType(edge.from());
            String to = Communities.owningType(edge.to());
            if (from.equals(to) || !dependsOn.containsKey(from) || !dependsOn.containsKey(to)) continue;
            dependsOn.get(from).add(to);
            outDegree.merge(from, 1, Integer::sum);
            inDegree.merge(to, 1, Integer::sum);
        }
        Map<String, Double> rank = new HashMap<>();
        int count = dependsOn.size();
        if (count == 0) return List.of();
        dependsOn.keySet().forEach(id -> rank.put(id, 1.0 / count));
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            Map<String, Double> next = new HashMap<>();
            double sink = 0.0;
            for (Map.Entry<String, List<String>> entry : dependsOn.entrySet()) {
                if (entry.getValue().isEmpty()) sink += rank.get(entry.getKey());
            }
            for (String id : dependsOn.keySet()) next.put(id, (1 - DAMPING + DAMPING * sink) / count);
            for (Map.Entry<String, List<String>> entry : dependsOn.entrySet()) {
                List<String> targets = entry.getValue();
                if (targets.isEmpty()) continue;
                double share = DAMPING * rank.get(entry.getKey()) / targets.size();
                for (String target : targets) next.merge(target, share, Double::sum);
            }
            rank.clear();
            rank.putAll(next);
        }
        return dependsOn.keySet().stream()
                .map(id -> new Score(id, rank.get(id), inDegree.getOrDefault(id, 0), outDegree.getOrDefault(id, 0)))
                .sorted(Comparator.comparingDouble(Score::pageRank).reversed().thenComparing(Score::id))
                .toList();
    }
}

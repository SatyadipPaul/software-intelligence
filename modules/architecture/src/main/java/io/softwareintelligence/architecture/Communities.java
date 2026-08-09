package io.softwareintelligence.architecture;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.RelationKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic clustering behind one interface, so the algorithm is a choice rather than a
 * commitment. Two strategies ship today: weakly connected components over dependency edges, and
 * k-core, which peels away peripheral types to leave the densely interconnected core.
 *
 * <p>A modularity-optimizing method such as Leiden can be added as another {@link Strategy} and
 * compared against these on the same graph. What matters for the product invariant is that every
 * strategy here is deterministic: no random seeds, no iteration-order dependence. Members are
 * returned sorted, and communities are ordered by size then by lowest member id.
 */
public final class Communities {
    public enum Strategy { CONNECTED_COMPONENTS, K_CORE }

    public record Community(String id, List<String> members, int cohesion) { }

    private static final Set<RelationKind> STRUCTURAL =
            Set.of(RelationKind.CALLS, RelationKind.DEPENDS_ON, RelationKind.EXTENDS, RelationKind.IMPLEMENTS, RelationKind.OVERRIDES);

    private Communities() { }

    public static List<Community> detect(CodeGraph graph, Strategy strategy, int k) {
        Map<String, Set<String>> adjacency = typeAdjacency(graph);
        return switch (strategy) {
            case CONNECTED_COMPONENTS -> components(adjacency);
            case K_CORE -> components(kCore(adjacency, k));
        };
    }

    /** Collapses member-level edges to the owning types: architecture is a type-level question. */
    private static Map<String, Set<String>> typeAdjacency(CodeGraph graph) {
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        for (GraphNode node : graph.nodes()) {
            if (isRepositoryType(graph, node)) adjacency.putIfAbsent(node.id(), new HashSet<>());
        }
        for (GraphEdge edge : graph.edges()) {
            if (!STRUCTURAL.contains(edge.kind())) continue;
            String from = owningType(edge.from());
            String to = owningType(edge.to());
            if (from.equals(to) || !adjacency.containsKey(from) || !adjacency.containsKey(to)) continue;
            adjacency.get(from).add(to);
            adjacency.get(to).add(from);
        }
        return adjacency;
    }

    private static List<Community> components(Map<String, Set<String>> adjacency) {
        List<Community> communities = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        List<String> ordered = adjacency.keySet().stream().sorted().toList();
        for (String start : ordered) {
            if (!visited.add(start)) continue;
            List<String> members = new ArrayList<>(List.of(start));
            ArrayDeque<String> queue = new ArrayDeque<>(List.of(start));
            int internalEdges = 0;
            while (!queue.isEmpty()) {
                String current = queue.remove();
                for (String neighbour : adjacency.getOrDefault(current, Set.of()).stream().sorted().toList()) {
                    internalEdges++;
                    if (!visited.add(neighbour)) continue;
                    members.add(neighbour);
                    queue.add(neighbour);
                }
            }
            members.sort(Comparator.naturalOrder());
            communities.add(new Community("community:" + members.get(0), List.copyOf(members), internalEdges / 2));
        }
        communities.sort(Comparator.comparingInt((Community community) -> -community.members().size())
                .thenComparing(Community::id));
        return List.copyOf(communities);
    }

    /** Repeatedly removes nodes with fewer than k neighbours; what remains is the k-core. */
    private static Map<String, Set<String>> kCore(Map<String, Set<String>> adjacency, int k) {
        Map<String, Set<String>> remaining = new LinkedHashMap<>();
        adjacency.forEach((node, neighbours) -> remaining.put(node, new HashSet<>(neighbours)));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String node : List.copyOf(remaining.keySet())) {
                if (remaining.get(node).size() >= k) continue;
                remaining.remove(node);
                remaining.values().forEach(neighbours -> neighbours.remove(node));
                changed = true;
            }
        }
        return remaining;
    }

    static boolean isRepositoryType(CodeGraph graph, GraphNode node) {
        if (!node.id().startsWith("type:") || node.id().contains("#") || node.id().contains(".field:")) return false;
        if (node.kind() == EntityKind.EXTERNAL_SYMBOL) return false;
        return graph.incoming(node.id()).stream().anyMatch(edge -> edge.kind() == RelationKind.DECLARES);
    }

    static String owningType(String id) {
        int member = id.indexOf('#');
        if (member > 0) return id.substring(0, member);
        int field = id.indexOf(".field:");
        return field > 0 ? id.substring(0, field) : id;
    }

    static Map<String, Set<String>> adjacencyFor(CodeGraph graph) {
        return new HashMap<>(typeAdjacency(graph));
    }
}

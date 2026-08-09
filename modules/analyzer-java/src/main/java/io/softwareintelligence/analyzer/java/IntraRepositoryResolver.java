package io.softwareintelligence.analyzer.java;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.Provenance;
import io.softwareintelligence.model.RelationKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves calls whose receiver and target are provable from source declared in the same
 * repository. It reads the receiver, method name, and arity recorded on the unresolved call node
 * rather than parsing an id back apart.
 *
 * <p>When a name and arity match more than one declared overload the call stays unresolved: an
 * ambiguous guess would be indistinguishable from proof in the exported graph.
 */
final class IntraRepositoryResolver {
    void resolve(CodeGraph graph) {
        Map<String, String> typesBySimpleName = new HashMap<>();
        Map<String, String> fieldsByOwnerAndName = new HashMap<>();
        for (GraphNode node : graph.nodes()) {
            if (isType(node)) typesBySimpleName.putIfAbsent(simpleName(node.name()), node.id());
            if (node.kind() == EntityKind.FIELD) {
                String owner = node.id().substring(0, node.id().indexOf(".field:"));
                fieldsByOwnerAndName.put(owner + "|" + node.name(), node.attributes().getOrDefault("declaredType", ""));
            }
        }

        List<GraphEdge> unresolved = graph.edges().stream()
                .filter(edge -> edge.kind() == RelationKind.CALLS && edge.to().startsWith("external:call:")).toList();
        for (GraphEdge call : unresolved) {
            Optional<GraphNode> target = graph.node(call.to());
            if (target.isEmpty()) continue;
            Map<String, String> attributes = target.get().attributes();
            String receiver = attributes.getOrDefault("receiver", "");
            String method = attributes.getOrDefault("method", "");
            String arity = attributes.getOrDefault("arity", "?");
            if (receiver.isBlank() || method.isBlank() || arity.equals("?")) continue;
            int hash = call.from().indexOf('#');
            if (hash < 0) continue;
            String declaredType = fieldsByOwnerAndName.get(call.from().substring(0, hash) + "|" + receiver);
            if (declaredType == null) continue;
            String resolvedType = typesBySimpleName.get(simpleName(declaredType));
            if (resolvedType == null) continue;
            List<GraphNode> candidates = declaredMethods(graph, resolvedType, method, arity);
            if (candidates.size() != 1) continue;
            Provenance origin = call.provenance();
            graph.addEdge(new GraphEdge(call.from(), candidates.get(0).id(), RelationKind.CALLS,
                    Map.of("resolution", "field-declared-type"),
                    new Provenance("INTRA_REPOSITORY_SYMBOL", 0.98, origin.file(), origin.line(), origin.column())));
        }
    }

    private static List<GraphNode> declaredMethods(CodeGraph graph, String typeId, String name, String arity) {
        List<GraphNode> matches = new ArrayList<>();
        for (GraphEdge edge : graph.outgoing(typeId)) {
            if (edge.kind() != RelationKind.DECLARES) continue;
            graph.node(edge.to())
                    .filter(node -> node.kind() == EntityKind.METHOD)
                    .filter(node -> node.name().equals(name))
                    .filter(node -> arity.equals(node.attributes().get("arity")))
                    .ifPresent(matches::add);
        }
        return matches;
    }

    private static boolean isType(GraphNode node) {
        return node.kind() == EntityKind.TYPE || node.kind() == EntityKind.INTERFACE || node.kind() == EntityKind.CONTROLLER
                || node.kind() == EntityKind.SERVICE || node.kind() == EntityKind.REPOSITORY_COMPONENT || node.kind() == EntityKind.ENTITY;
    }

    private static String simpleName(String type) {
        String normalized = type.replaceAll("<.*>", "");
        int dot = normalized.lastIndexOf('.');
        return dot < 0 ? normalized : normalized.substring(dot + 1);
    }
}

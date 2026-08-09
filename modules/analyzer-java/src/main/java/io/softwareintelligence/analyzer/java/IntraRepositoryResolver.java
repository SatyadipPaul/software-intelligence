package io.softwareintelligence.analyzer.java;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.Provenance;
import io.softwareintelligence.model.RelationKind;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Resolves calls whose receiver and target are provable from source declared in the same repository. */
final class IntraRepositoryResolver {
    void resolve(CodeGraph graph) {
        Map<String, String> typesBySimpleName = new HashMap<>();
        Map<String, String> fieldsByOwnerAndName = new HashMap<>();
        Map<String, String> methods = new HashMap<>();
        for (GraphNode node : graph.nodes()) {
            if (isType(node)) typesBySimpleName.putIfAbsent(simpleName(node.name()), node.id());
            if (node.kind() == EntityKind.FIELD) {
                String owner = node.id().substring(0, node.id().indexOf(".field:"));
                fieldsByOwnerAndName.put(owner + "|" + node.name(), node.attributes().getOrDefault("declaredType", ""));
            }
            if (node.kind() == EntityKind.METHOD) methods.put(node.id(), node.id());
        }

        List<GraphEdge> calls = graph.edges().stream().filter(edge -> edge.kind() == RelationKind.CALLS && edge.to().startsWith("external:call:")).toList();
        for (GraphEdge call : calls) {
            String target = call.to().substring("external:call:".length());
            int slash = target.lastIndexOf('/');
            int dot = target.lastIndexOf('.', slash);
            if (slash < 0 || dot < 0) continue;
            String receiver = target.substring(0, dot);
            String methodAndArity = target.substring(dot + 1);
            String owner = call.from().substring(0, call.from().indexOf('#'));
            String declaredType = fieldsByOwnerAndName.get(owner + "|" + receiver);
            if (declaredType == null) continue;
            String resolvedType = typesBySimpleName.get(simpleName(declaredType));
            if (resolvedType == null) continue;
            String resolvedMethod = methods.get(resolvedType + "#" + methodAndArity);
            if (resolvedMethod == null) continue;
            Provenance p = call.provenance();
            graph.addEdge(new GraphEdge(call.from(), resolvedMethod, RelationKind.CALLS,
                    Map.of("resolution", "field-declared-type"),
                    new Provenance("INTRA_REPOSITORY_SYMBOL", 0.98, p.file(), p.line(), p.column())));
        }
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

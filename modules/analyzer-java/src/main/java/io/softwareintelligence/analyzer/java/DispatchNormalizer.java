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

/**
 * Normalizes interface and abstract dispatch. A call proven to land on a declared method that
 * in-repository types override is also recorded against each override, because "what breaks if I
 * change this" must follow the implementation the container or the JVM will actually run.
 *
 * <p>The number of candidates is recorded on every edge and drives confidence: a single
 * implementation is near-certain, several are genuinely alternative targets and are labelled as
 * such rather than being silently collapsed to one.
 */
final class DispatchNormalizer {
    static final String RESOLVER = "DISPATCH_NORMALIZED";

    void normalize(CodeGraph graph) {
        Map<String, List<String>> overridesOf = new HashMap<>();
        for (GraphEdge edge : graph.edges()) {
            if (edge.kind() == RelationKind.OVERRIDES) {
                overridesOf.computeIfAbsent(edge.to(), ignored -> new ArrayList<>()).add(edge.from());
            }
        }
        if (overridesOf.isEmpty()) return;

        List<GraphEdge> calls = graph.edges().stream()
                .filter(edge -> edge.kind() == RelationKind.CALLS)
                .filter(edge -> overridesOf.containsKey(edge.to()))
                .toList();
        for (GraphEdge call : calls) {
            List<String> implementations = overridesOf.get(call.to()).stream().filter(id -> isInRepository(graph, id)).sorted().toList();
            if (implementations.isEmpty()) continue;
            double confidence = implementations.size() == 1 ? 0.95 : 0.60;
            Provenance origin = call.provenance();
            for (String implementation : implementations) {
                if (implementation.equals(call.from())) continue;
                graph.addEdge(new GraphEdge(call.from(), implementation, RelationKind.CALLS,
                        Map.of("resolution", "interface-dispatch",
                                "declaredTarget", call.to(),
                                "candidates", Integer.toString(implementations.size())),
                        new Provenance(RESOLVER, confidence, origin.file(), origin.line(), origin.column())));
            }
        }
    }

    /** True when the id belongs to a method the repository itself declares. */
    private static boolean isInRepository(CodeGraph graph, String methodId) {
        return graph.node(methodId).map(GraphNode::kind).filter(kind -> kind == EntityKind.METHOD).isPresent()
                && graph.incoming(methodId).stream().anyMatch(edge -> edge.kind() == RelationKind.DECLARES);
    }
}

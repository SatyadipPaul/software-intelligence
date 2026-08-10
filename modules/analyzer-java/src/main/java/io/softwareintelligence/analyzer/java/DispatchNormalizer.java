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

    /**
     * Above this many in-repository implementations, the fan-out is recorded as a count on the
     * declared method rather than as edges.
     *
     * <p>Measured on jackson-databind: {@code ValueDeserializer.deserialize} has 166 implementations
     * and produced 13,924 edges on its own. "This call might reach any of 166 places" is not an
     * answer anyone can act on, and at that volume it drowns the edges that are. The count is kept,
     * so the fact is not lost - only the unusable enumeration of it.
     */
    static final int MAX_FANOUT = 12;

    /**
     * Every class overrides these, so their candidate set is "the whole repository" and carries no
     * information about what a change actually reaches.
     */
    private static final String UNIVERSAL_OWNER = "type:java.lang.Object#";

    void normalize(CodeGraph graph) {
        Map<String, List<String>> overridesOf = new HashMap<>();
        for (GraphEdge edge : graph.edges()) {
            if (edge.kind() == RelationKind.OVERRIDES) {
                overridesOf.computeIfAbsent(edge.to(), ignored -> new ArrayList<>()).add(edge.from());
            }
        }
        if (overridesOf.isEmpty()) return;

        // The implementation count is recorded on every overridden method, whether or not the
        // fan-out is emitted as edges, so "how polymorphic is this" stays answerable.
        for (Map.Entry<String, List<String>> entry : overridesOf.entrySet()) {
            List<String> inRepository = entry.getValue().stream().filter(id -> isInRepository(graph, id)).toList();
            if (inRepository.isEmpty()) continue;
            graph.node(entry.getKey()).ifPresent(target -> {
                Map<String, String> attributes = new java.util.LinkedHashMap<>(target.attributes());
                attributes.put("implementations", Integer.toString(inRepository.size()));
                if (inRepository.size() > MAX_FANOUT) attributes.put("dispatchFanOutSuppressed", "true");
                graph.upsertNode(new GraphNode(target.id(), target.kind(), target.name(),
                        Map.copyOf(attributes), target.provenance()), true);
            });
        }

        List<GraphEdge> calls = graph.edges().stream()
                .filter(edge -> edge.kind() == RelationKind.CALLS)
                .filter(edge -> overridesOf.containsKey(edge.to()))
                .filter(edge -> !edge.to().startsWith(UNIVERSAL_OWNER))
                .toList();
        for (GraphEdge call : calls) {
            List<String> implementations = overridesOf.get(call.to()).stream().filter(id -> isInRepository(graph, id)).sorted().toList();
            if (implementations.isEmpty() || implementations.size() > MAX_FANOUT) continue;
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

package io.softwareintelligence.architecture;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.Provenance;
import io.softwareintelligence.model.RelationKind;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Groups the operational surface into business capabilities using only what the repository states:
 * the first stable segment of an HTTP route, the root of a topic name, and the entities those flows
 * touch. {@code /owners/{ownerId}/pets/new} and {@code /owners/find} are one capability because the
 * application says they are, not because a model guessed it.
 *
 * <p>This is the deterministic floor of the semantic layer. Naming a capability in domain language
 * — "Owner registration" rather than "owners" — is exactly the ambiguous, high-value judgement that
 * {@code EnrichmentPlanner} exists to budget for; nothing here invents a name the source lacks.
 */
public final class Capabilities {
    public static final String RESOLVER = "CAPABILITY_GROUPING";

    public record Capability(String id, String name, List<String> entryPoints, List<String> entities) { }

    private Capabilities() { }

    public static List<Capability> detect(CodeGraph graph) {
        Map<String, Set<String>> entryPointsByName = new TreeMap<>();
        Map<String, Set<String>> entitiesByName = new TreeMap<>();

        for (GraphNode node : graph.nodes()) {
            String name = capabilityName(node);
            if (name == null) continue;
            entryPointsByName.computeIfAbsent(name, ignored -> new LinkedHashSet<>()).add(node.id());
            entitiesByName.computeIfAbsent(name, ignored -> new LinkedHashSet<>()).addAll(touchedEntities(graph, node));
        }

        List<Capability> capabilities = new ArrayList<>();
        entryPointsByName.forEach((name, entryPoints) -> {
            Capability capability = new Capability("capability:" + name, name,
                    List.copyOf(entryPoints), List.copyOf(entitiesByName.getOrDefault(name, Set.of())));
            capabilities.add(capability);
            record(graph, capability);
        });
        return List.copyOf(capabilities);
    }

    private static void record(CodeGraph graph, Capability capability) {
        Provenance provenance = new Provenance(RESOLVER, 0.85, "", 0, 0);
        graph.upsertNode(new GraphNode(capability.id(), EntityKind.BUSINESS_CAPABILITY, capability.name(),
                Map.of("entryPoints", Integer.toString(capability.entryPoints().size()),
                        "entities", String.join(",", capability.entities())), provenance), true);
        for (String entryPoint : capability.entryPoints()) {
            graph.node(entryPoint).ifPresent(node -> graph.addEdge(new GraphEdge(capability.id(), entryPoint,
                    RelationKind.PARTICIPATES_IN, Map.of("role", "entry-point"),
                    new Provenance(RESOLVER, 0.85, node.provenance().file(), node.provenance().line(), node.provenance().column()))));
        }
        for (String entity : capability.entities()) {
            graph.node(entity).ifPresent(node -> graph.addEdge(new GraphEdge(capability.id(), entity,
                    RelationKind.PARTICIPATES_IN, Map.of("role", "entity"),
                    new Provenance(RESOLVER, 0.85, node.provenance().file(), node.provenance().line(), node.provenance().column()))));
        }
    }

    /** The first path segment that is not a variable, or the first dotted segment of a topic. */
    private static String capabilityName(GraphNode node) {
        if (node.kind() == EntityKind.ENDPOINT) {
            String path = node.attributes().getOrDefault("path", "");
            for (String segment : path.split("/")) {
                if (segment.isBlank() || segment.startsWith("{")) continue;
                return segment.toLowerCase(Locale.ROOT).replace(".html", "");
            }
            return null;
        }
        if (node.kind() == EntityKind.TOPIC) {
            String name = node.name();
            if (name.startsWith("<")) return null;
            int dot = name.indexOf('.');
            return (dot < 0 ? name : name.substring(0, dot)).toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /** Entities and tables reachable from an entry point through the workflow already recorded. */
    private static Set<String> touchedEntities(CodeGraph graph, GraphNode entryPoint) {
        Set<String> touched = new LinkedHashSet<>();
        graph.node("workflow:" + entryPoint.id()).ifPresent(workflow -> {
            for (GraphEdge edge : graph.outgoing(workflow.id())) {
                if (!"effect".equals(edge.attributes().get("role"))) continue;
                graph.node(edge.to()).filter(node -> node.kind() == EntityKind.DATABASE_TABLE
                        || node.kind() == EntityKind.ENTITY || node.kind() == EntityKind.TOPIC)
                        .ifPresent(node -> touched.add(node.id()));
            }
        });
        return touched;
    }
}

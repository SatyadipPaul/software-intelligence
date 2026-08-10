package io.softwareintelligence.architecture;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.Provenance;
import io.softwareintelligence.model.RelationKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Detects subsystems from build layout and package structure, which is where Java projects actually
 * express module boundaries. A multi-module build gives one module per source root; a single-module
 * project falls back to the deepest common package prefix that still splits the code into more than
 * one group.
 *
 * <p>Coupling between modules is counted from existing structural edges, so a reported boundary
 * violation points at real call sites rather than at a naming convention.
 */
public final class Modules {
    public static final String RESOLVER = "MODULE_LAYOUT";

    public record Subsystem(String id, String name, List<String> types, Map<String, Integer> dependsOn) { }

    private Modules() { }

    public static List<Subsystem> detect(CodeGraph graph) {
        Map<String, String> moduleOfType = new LinkedHashMap<>();
        Map<String, List<String>> typesByModule = new TreeMap<>();
        List<GraphNode> types = graph.nodes().stream()
                .filter(node -> Communities.isRepositoryType(graph, node))
                .sorted(Comparator.comparing(GraphNode::id)).toList();
        if (types.isEmpty()) return List.of();

        Map<String, String> sourceRootOf = sourceRoots(graph);
        Map<String, String> packageOf = packages(graph);
        boolean multiModule = new TreeSet<>(sourceRootOf.values()).size() > 1;
        for (GraphNode type : types) {
            String name = multiModule
                    ? sourceRootOf.getOrDefault(type.id(), "<root>")
                    : packageOf.getOrDefault(type.id(), "<default>");
            moduleOfType.put(type.id(), name);
            typesByModule.computeIfAbsent(name, ignored -> new ArrayList<>()).add(type.id());
        }

        Map<String, Map<String, Integer>> coupling = new TreeMap<>();
        for (GraphEdge edge : graph.edges()) {
            if (edge.kind() != RelationKind.CALLS && edge.kind() != RelationKind.DEPENDS_ON) continue;
            String from = moduleOfType.get(Communities.owningType(edge.from()));
            String to = moduleOfType.get(Communities.owningType(edge.to()));
            if (from == null || to == null || from.equals(to)) continue;
            coupling.computeIfAbsent(from, ignored -> new TreeMap<>()).merge(to, 1, Integer::sum);
        }

        List<Subsystem> subsystems = new ArrayList<>();
        typesByModule.forEach((name, members) -> {
            // A sorted, order-preserving map: Map.copyOf would randomize iteration order per JVM
            // run, and that order reaches the emitted edges and the printed report.
            Subsystem subsystem = new Subsystem("module:" + name, name, List.copyOf(members),
                    java.util.Collections.unmodifiableMap(new TreeMap<>(coupling.getOrDefault(name, Map.of()))));
            subsystems.add(subsystem);
            record(graph, subsystem, members);
        });
        return List.copyOf(subsystems);
    }

    private static void record(CodeGraph graph, Subsystem subsystem, List<String> members) {
        Provenance provenance = new Provenance(RESOLVER, 1.0, "", 0, 0);
        graph.upsertNode(new GraphNode(subsystem.id(), EntityKind.MODULE, subsystem.name(),
                Map.of("types", Integer.toString(members.size()),
                        "efferentCoupling", Integer.toString(subsystem.dependsOn().values().stream().mapToInt(Integer::intValue).sum())),
                provenance), true);
        for (String member : members) {
            graph.node(member).ifPresent(type -> graph.addEdge(new GraphEdge(subsystem.id(), member, RelationKind.CONTAINS,
                    Map.of("resolution", "module-layout"),
                    new Provenance(RESOLVER, 1.0, type.provenance().file(), type.provenance().line(), type.provenance().column()))));
        }
        subsystem.dependsOn().forEach((target, weight) -> graph.addEdge(new GraphEdge(subsystem.id(), "module:" + target,
                RelationKind.DEPENDS_ON, Map.of("weight", Integer.toString(weight), "resolution", "module-coupling"), provenance)));
    }

    /** Maps each type to its Maven/Gradle source root, taken from the declaring file path. */
    private static Map<String, String> sourceRoots(CodeGraph graph) {
        Map<String, String> roots = new LinkedHashMap<>();
        for (GraphNode node : graph.nodes()) {
            if (!Communities.isRepositoryType(graph, node)) continue;
            String file = node.provenance().file();
            int marker = file.indexOf("/src/");
            roots.put(node.id(), marker <= 0 ? "<root>" : file.substring(0, marker));
        }
        return roots;
    }

    /**
     * Maps each type to the package its declaring file declares.
     *
     * <p>Read from the graph rather than parsed out of the qualified name: a nested type is named
     * {@code pkg.Outer.Inner}, so trimming the last segment would make every outer class look like
     * its own package - and, before this was fixed, its own module.
     */
    private static Map<String, String> packages(CodeGraph graph) {
        Map<String, String> packageOfFile = new LinkedHashMap<>();
        for (GraphNode node : graph.nodes()) {
            if (node.kind() != EntityKind.FILE) continue;
            for (GraphEdge edge : graph.outgoing(node.id())) {
                if (edge.kind() == RelationKind.DECLARES && edge.to().startsWith("package:")) {
                    packageOfFile.put(node.id(), edge.to().substring("package:".length()));
                }
            }
        }
        Map<String, String> packageOfType = new LinkedHashMap<>();
        for (GraphNode node : graph.nodes()) {
            if (!Communities.isRepositoryType(graph, node)) continue;
            String owner = declaringFile(graph, node.id(), 0);
            String declared = owner == null ? null : packageOfFile.get(owner);
            packageOfType.put(node.id(), declared != null ? declared : packageFromName(node.name()));
        }
        return packageOfType;
    }

    /**
     * The package part of a qualified name, for a graph that carries no package declaration - a
     * snapshot from an older schema, or a type in the default package.
     *
     * <p>Uses Java's own naming convention rather than "everything before the last dot": package
     * segments are lower case and type segments are capitalized, so {@code perf.MediaItem.Content}
     * yields {@code perf} and not {@code perf.MediaItem}.
     */
    private static String packageFromName(String qualifiedName) {
        StringBuilder packageName = new StringBuilder();
        for (String segment : qualifiedName.split("\\.")) {
            if (segment.isEmpty() || Character.isUpperCase(segment.charAt(0))) break;
            if (packageName.length() > 0) packageName.append('.');
            packageName.append(segment);
        }
        return packageName.length() == 0 ? "<default>" : packageName.toString();
    }

    /** Walks DECLARES upwards from a type, through any enclosing types, to the file that holds it. */
    private static String declaringFile(CodeGraph graph, String id, int depth) {
        if (depth > 16) return null;
        for (GraphEdge edge : graph.incoming(id)) {
            if (edge.kind() != RelationKind.DECLARES) continue;
            if (edge.from().startsWith("file:")) return edge.from();
            String enclosing = declaringFile(graph, edge.from(), depth + 1);
            if (enclosing != null) return enclosing;
        }
        return null;
    }
}

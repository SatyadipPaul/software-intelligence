package io.softwareintelligence.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable, self-describing graph snapshots and the diff between two of them.
 *
 * <p>A snapshot is the JSON export plus its schema version; reading one back checks that version
 * before trusting the contents, because ids from an older schema are not comparable to today's.
 * The diff is what makes change impact answerable across commits: added, removed, and re-resolved
 * relationships, where "re-resolved" means the same relationship is now proven by a different
 * resolver or at a different confidence.
 */
public final class GraphSnapshot {

    public record Diff(List<GraphNode> addedNodes, List<GraphNode> removedNodes,
                       List<GraphEdge> addedEdges, List<GraphEdge> removedEdges,
                       List<Reresolved> reresolved) {
        public boolean isEmpty() {
            return addedNodes.isEmpty() && removedNodes.isEmpty() && addedEdges.isEmpty()
                    && removedEdges.isEmpty() && reresolved.isEmpty();
        }
    }

    public record Reresolved(GraphEdge before, GraphEdge after) { }

    private GraphSnapshot() { }

    public static void write(CodeGraph graph, Path destination) throws IOException {
        if (destination.getParent() != null) Files.createDirectories(destination.getParent());
        Files.writeString(destination, GraphJsonWriter.write(graph), StandardCharsets.UTF_8);
    }

    /** Reads a snapshot written by {@link #write}, refusing one from a different schema version. */
    public static CodeGraph read(Path source) throws IOException {
        String json = Files.readString(source, StandardCharsets.UTF_8);
        String version = GraphJsonReader.version(json);
        if (!GraphJsonWriter.SCHEMA_VERSION.equals(version)) {
            throw new IOException("snapshot " + source + " uses schema " + version
                    + " but this build writes " + GraphJsonWriter.SCHEMA_VERSION + "; re-analyze the repository");
        }
        return GraphJsonReader.read(json);
    }

    public static Diff diff(CodeGraph before, CodeGraph after) {
        Map<String, GraphNode> beforeNodes = index(before);
        Map<String, GraphNode> afterNodes = index(after);
        List<GraphNode> added = new ArrayList<>();
        List<GraphNode> removed = new ArrayList<>();
        afterNodes.forEach((id, node) -> { if (!beforeNodes.containsKey(id)) added.add(node); });
        beforeNodes.forEach((id, node) -> { if (!afterNodes.containsKey(id)) removed.add(node); });

        Map<String, GraphEdge> beforeEdges = edgeIndex(before);
        Map<String, GraphEdge> afterEdges = edgeIndex(after);
        List<GraphEdge> addedEdges = new ArrayList<>();
        List<GraphEdge> removedEdges = new ArrayList<>();
        List<Reresolved> reresolved = new ArrayList<>();
        afterEdges.forEach((key, edge) -> {
            GraphEdge previous = beforeEdges.get(key);
            if (previous == null) addedEdges.add(edge);
            else if (!previous.provenance().resolver().equals(edge.provenance().resolver())
                    || previous.provenance().confidence() != edge.provenance().confidence()) {
                reresolved.add(new Reresolved(previous, edge));
            }
        });
        beforeEdges.forEach((key, edge) -> { if (!afterEdges.containsKey(key)) removedEdges.add(edge); });

        added.sort(java.util.Comparator.comparing(GraphNode::id));
        removed.sort(java.util.Comparator.comparing(GraphNode::id));
        addedEdges.sort(GraphSnapshot::compare);
        removedEdges.sort(GraphSnapshot::compare);
        return new Diff(List.copyOf(added), List.copyOf(removed), List.copyOf(addedEdges), List.copyOf(removedEdges), List.copyOf(reresolved));
    }

    public static String render(Diff diff) {
        StringBuilder text = new StringBuilder(String.format(
                "GRAPH DIFF: +%d/-%d nodes, +%d/-%d edges, %d re-resolved%n",
                diff.addedNodes().size(), diff.removedNodes().size(),
                diff.addedEdges().size(), diff.removedEdges().size(), diff.reresolved().size()));
        diff.removedNodes().forEach(node -> text.append("  - ").append(node.id()).append(" [").append(node.kind()).append("]\n"));
        diff.addedNodes().forEach(node -> text.append("  + ").append(node.id()).append(" [").append(node.kind()).append("]\n"));
        diff.reresolved().forEach(change -> text.append("  ~ ").append(change.after().from()).append(" -")
                .append(change.after().kind()).append("-> ").append(change.after().to())
                .append(" : ").append(change.before().provenance().resolver()).append(" -> ")
                .append(change.after().provenance().resolver()).append('\n'));
        return text.toString();
    }

    /** Types whose declared surface changed between two snapshots — the input to a what-if query. */
    public static List<String> changedSymbols(Diff diff) {
        java.util.Set<String> changed = new java.util.LinkedHashSet<>();
        diff.addedNodes().forEach(node -> changed.add(owner(node.id())));
        diff.removedNodes().forEach(node -> changed.add(owner(node.id())));
        diff.addedEdges().forEach(edge -> changed.add(owner(edge.from())));
        diff.removedEdges().forEach(edge -> changed.add(owner(edge.from())));
        return changed.stream().filter(id -> id.startsWith("type:")).sorted().toList();
    }

    private static String owner(String id) {
        int member = id.indexOf('#');
        if (member > 0) return id.substring(0, member);
        int field = id.indexOf(".field:");
        return field > 0 ? id.substring(0, field) : id;
    }

    private static int compare(GraphEdge left, GraphEdge right) {
        return edgeKey(left).compareTo(edgeKey(right));
    }

    private static Map<String, GraphNode> index(CodeGraph graph) {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        graph.nodes().forEach(node -> nodes.put(node.id(), node));
        return nodes;
    }

    private static Map<String, GraphEdge> edgeIndex(CodeGraph graph) {
        Map<String, GraphEdge> edges = new LinkedHashMap<>();
        graph.edges().forEach(edge -> edges.put(edgeKey(edge), edge));
        return edges;
    }

    private static String edgeKey(GraphEdge edge) {
        return edge.from() + '|' + edge.to() + '|' + edge.kind();
    }
}

package io.softwareintelligence.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Graph-only analyses: no LLM inference and no relationship without recorded evidence. */
public final class GraphQueries {
    private GraphQueries() { }

    /**
     * The result of looking a symbol up by id, name, or id suffix. {@code alternatives} lists the
     * other nodes that matched the same way, so a caller can report an ambiguous query instead of
     * silently analyzing an arbitrary match.
     */
    public record SymbolMatch(GraphNode node, List<GraphNode> alternatives) {
        public boolean ambiguous() { return !alternatives.isEmpty(); }
    }

    public static Optional<GraphNode> findSymbol(CodeGraph graph, String query) {
        return resolveSymbol(graph, query).map(SymbolMatch::node);
    }

    /**
     * Resolves a query through fixed tiers: exact id, then exact source name, then source id
     * suffix, then external name and external id suffix. Within a tier, matches are ordered by id
     * so the selected symbol never depends on file-walk or insertion order.
     */
    public static Optional<SymbolMatch> resolveSymbol(CodeGraph graph, String query) {
        Optional<GraphNode> exactId = graph.node(query);
        if (exactId.isPresent()) return exactId.map(node -> new SymbolMatch(node, List.of()));
        List<List<GraphNode>> tiers = List.of(
                candidates(graph, node -> isSource(node) && node.name().equals(query)),
                candidates(graph, node -> isSource(node) && isSuffixMatch(node.id(), query)),
                candidates(graph, node -> node.name().equals(query)),
                candidates(graph, node -> isSuffixMatch(node.id(), query)));
        for (List<GraphNode> tier : tiers) {
            if (tier.isEmpty()) continue;
            return Optional.of(new SymbolMatch(tier.get(0), List.copyOf(tier.subList(1, tier.size()))));
        }
        return Optional.empty();
    }

    /** Traverses incoming references: answers "what is affected if this symbol disappears?". */
    public static ImpactReport impact(CodeGraph graph, GraphNode subject, int maxDepth) {
        List<ImpactReport.ImpactPath> direct = new ArrayList<>();
        List<ImpactReport.ImpactPath> transitive = new ArrayList<>();
        ArrayDeque<PathState> queue = new ArrayDeque<>();
        Set<String> seeds = declaredMembers(graph, subject.id());
        for (String seed : seeds) queue.add(new PathState(seed, List.of()));
        Set<String> visited = new HashSet<>(seeds);
        while (!queue.isEmpty()) {
            PathState current = queue.remove();
            if (current.edges().size() >= maxDepth) continue;
            for (GraphEdge edge : graph.incoming(current.nodeId())) {
                if (!impactRelevant(edge.kind())) continue;
                if (!visited.add(edge.from())) continue;
                Optional<GraphNode> source = graph.node(edge.from());
                if (source.isEmpty()) continue;
                List<GraphEdge> path = new ArrayList<>(current.edges());
                path.add(edge);
                ImpactReport.ImpactPath result = new ImpactReport.ImpactPath(source.get(), List.copyOf(path));
                if (path.size() == 1) direct.add(result); else transitive.add(result);
                queue.add(new PathState(source.get().id(), path));
            }
        }
        return new ImpactReport(subject, List.copyOf(direct), List.copyOf(transitive));
    }

    public static List<GraphEdge> outgoing(CodeGraph graph, String from) {
        return graph.outgoing(from);
    }

    /** Builds the minimum useful context around a symbol without returning the full repository graph. */
    public static ContextPacket context(CodeGraph graph, GraphNode subject, int depth) {
        ImpactReport impact = impact(graph, subject, depth);
        List<ImpactReport.ImpactPath> allImpact = new ArrayList<>(impact.direct());
        allImpact.addAll(impact.transitive());
        List<GraphNode> callers = allImpact.stream().map(ImpactReport.ImpactPath::target)
                .filter(node -> node.kind() == EntityKind.METHOD || node.kind() == EntityKind.TYPE).distinct().toList();
        List<GraphNode> endpoints = allImpact.stream().map(ImpactReport.ImpactPath::target)
                .filter(node -> node.kind() == EntityKind.ENDPOINT).distinct().toList();
        // Downstream is collected from the subject and from the members it declares. "What does
        // this touch" is as much a part of a symbol's context as "what touches this", and for an
        // entry point such as an endpoint or a listener it is the only useful direction.
        List<GraphEdge> downstream = downstream(graph, subject.id());
        List<GraphNode> dependencies = downstream.stream()
                .map(edge -> graph.node(edge.to()).orElse(null)).filter(Objects::nonNull)
                .filter(node -> !node.id().equals(subject.id())).distinct().toList();
        Map<String, GraphEdge> evidence = new LinkedHashMap<>();
        allImpact.stream().flatMap(path -> path.evidence().stream()).forEach(edge -> evidence.put(evidenceKey(edge), edge));
        downstream.forEach(edge -> evidence.put(evidenceKey(edge), edge));
        return new ContextPacket(subject, callers, endpoints, dependencies, List.copyOf(evidence.values()));
    }

    /**
     * The operational surface of a symbol: what it and its declared members expose, persist,
     * publish, consume, configure, or depend on. Forward calls are deliberately excluded — the
     * call graph is already covered in reverse by impact, and including it forward would turn a
     * minimum-sufficient packet into most of the repository.
     */
    private static List<GraphEdge> downstream(CodeGraph graph, String subjectId) {
        List<GraphEdge> edges = new ArrayList<>();
        for (String member : declaredMembers(graph, subjectId).stream().sorted().toList()) {
            for (GraphEdge edge : graph.outgoing(member)) {
                if (edge.kind() == RelationKind.DECLARES || edge.kind() == RelationKind.CONTAINS) continue;
                if (edge.kind() == RelationKind.CALLS && !member.equals(subjectId)) continue;
                edges.add(edge);
            }
        }
        return List.copyOf(edges);
    }

    private record PathState(String nodeId, List<GraphEdge> edges) { }

    private static List<GraphNode> candidates(CodeGraph graph, java.util.function.Predicate<GraphNode> filter) {
        return graph.nodes().stream().filter(filter).sorted(Comparator.comparing(GraphNode::id)).toList();
    }

    private static boolean isSource(GraphNode node) { return node.kind() != EntityKind.EXTERNAL_SYMBOL; }

    /**
     * A suffix only matches on an identifier boundary, so {@code PaymentService} matches
     * {@code type:demo.PaymentService} but {@code Service} does not.
     */
    private static boolean isSuffixMatch(String id, String query) {
        if (query.isEmpty() || !id.endsWith(query) || id.length() == query.length()) return false;
        char boundary = id.charAt(id.length() - query.length() - 1);
        return !Character.isJavaIdentifierPart(boundary) || !Character.isJavaIdentifierPart(query.charAt(0));
    }

    private static String evidenceKey(GraphEdge edge) {
        return edge.from() + '|' + edge.to() + '|' + edge.kind() + '|' + edge.provenance().line() + '|' + edge.provenance().column();
    }

    private static Set<String> declaredMembers(CodeGraph graph, String root) {
        Set<String> members = new HashSet<>(Set.of(root));
        ArrayDeque<String> queue = new ArrayDeque<>(List.of(root));
        while (!queue.isEmpty()) {
            for (GraphEdge edge : graph.outgoing(queue.remove())) {
                if (edge.kind() == RelationKind.DECLARES && members.add(edge.to())) queue.add(edge.to());
            }
        }
        return members;
    }

    private static boolean impactRelevant(RelationKind kind) {
        return switch (kind) {
            case CALLS, CREATES, IMPORTS, EXTENDS, IMPLEMENTS, OVERRIDES, EXPOSES, PERSISTS, PUBLISHES, CONSUMES, CONFIGURES, DEPENDS_ON, PARTICIPATES_IN -> true;
            default -> false;
        };
    }
}

package io.softwareintelligence.queryengine;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.ContextPacket;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.GraphQueries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Classifies a natural-language question and plans retrieval for it. Classification is a keyword
 * decision, not a model call: it is fast, inspectable, and wrong in ways a user can see and correct.
 *
 * <p>The plan is adaptive in the sense that matters — an impact question traverses the graph, a
 * lookup question retrieves symbols, an endpoint question filters to routes — so a question does
 * not pay for a traversal it does not need.
 */
public final class QueryPlanner {
    public enum QueryKind { IMPACT, LOOKUP, ENDPOINT, PERSISTENCE, SECURITY, STRUCTURE }

    public record Plan(QueryKind kind, String subject, int depth, String rationale) { }

    public record Answerable(Plan plan, Optional<GraphNode> subject, List<Bm25Index.Hit> retrieved, Optional<ContextPacket> context) { }

    private QueryPlanner() { }

    public static Plan classify(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "impact", "affect", "break", "what if", "change", "remove", "delete", "depends on")) {
            return new Plan(QueryKind.IMPACT, subjectOf(question), 4, "asks what a change reaches, so the graph is traversed backwards from the subject");
        }
        // Security is checked before the HTTP surface: "which roles guard the admin API" is a
        // question about authorization that merely mentions an API.
        if (containsAny(lower, "secure", "security", "role", "auth", "permission", "guard")) {
            return new Plan(QueryKind.SECURITY, subjectOf(question), 3, "asks about authorization rules");
        }
        if (containsAny(lower, "endpoint", "route", "url", "http", "rest", "api")) {
            return new Plan(QueryKind.ENDPOINT, subjectOf(question), 3, "asks about the exposed HTTP surface");
        }
        if (containsAny(lower, "table", "database", "persist", "query", "sql", "column")) {
            return new Plan(QueryKind.PERSISTENCE, subjectOf(question), 3, "asks about persistence, so table and query edges lead");
        }
        if (containsAny(lower, "architecture", "module", "subsystem", "structure", "layer", "coupling")) {
            return new Plan(QueryKind.STRUCTURE, subjectOf(question), 2, "asks about structure rather than a single symbol");
        }
        return new Plan(QueryKind.LOOKUP, subjectOf(question), 2, "no traversal intent detected, so retrieval leads");
    }

    /** Runs the plan and returns everything an answer may cite — and nothing else. */
    public static Answerable plan(CodeGraph graph, Bm25Index index, String question, int retrievalLimit) {
        Plan plan = classify(question);
        List<Bm25Index.Hit> hits = index.search(question, retrievalLimit);
        Optional<GraphNode> subject = GraphQueries.findSymbol(graph, plan.subject());
        if (subject.isEmpty()) subject = hits.stream().map(Bm25Index.Hit::node).filter(QueryPlanner::isAnchorable).findFirst();
        Optional<ContextPacket> context = subject.map(node -> GraphQueries.context(graph, node, plan.depth()));
        return new Answerable(plan, subject, hits, context);
    }

    /**
     * Compresses a packet to a token budget, dropping the least load-bearing evidence first.
     * Endpoints and direct callers survive longest because they carry the operational answer.
     */
    public static ContextPacket compress(ContextPacket packet, int tokenBudget) {
        if (estimateTokens(packet) <= tokenBudget) return packet;
        List<GraphNode> callers = new ArrayList<>(packet.callers());
        List<GraphEdge> evidence = new ArrayList<>(packet.evidence());
        Set<String> keep = new LinkedHashSet<>();
        packet.endpoints().forEach(node -> keep.add(node.id()));
        packet.dependencies().forEach(node -> keep.add(node.id()));

        // Edges that touch the subject rank first. On a hub symbol the packet holds thousands of
        // equal-confidence edges, and dropping from the end of a confidence-only sort leaves
        // whichever unrelated path edge happened to sort last - an answer about the wrong symbol.
        String subjectId = packet.subject().id();
        evidence.sort(Comparator.comparingInt((GraphEdge edge) -> touches(edge, subjectId) ? 0 : 1)
                .thenComparing(Comparator.comparingDouble((GraphEdge edge) -> edge.provenance().confidence()).reversed())
                .thenComparing(GraphEdge::from).thenComparing(GraphEdge::to));
        while (estimateTokens(new ContextPacket(packet.subject(), callers, packet.endpoints(), packet.dependencies(), evidence)) > tokenBudget) {
            if (!callers.isEmpty() && callers.size() > packet.endpoints().size()) {
                callers.remove(callers.size() - 1);
                continue;
            }
            if (evidence.size() > 1) {
                evidence.remove(evidence.size() - 1);
                continue;
            }
            break;
        }
        return new ContextPacket(packet.subject(), List.copyOf(callers), packet.endpoints(), packet.dependencies(), List.copyOf(evidence));
    }

    /** A deliberately simple, deterministic estimate: budgeting must not depend on a tokenizer. */
    public static int estimateTokens(ContextPacket packet) {
        int tokens = length(packet.subject());
        for (GraphNode node : packet.callers()) tokens += length(node);
        for (GraphNode node : packet.endpoints()) tokens += length(node);
        for (GraphNode node : packet.dependencies()) tokens += length(node);
        for (GraphEdge edge : packet.evidence()) {
            tokens += (edge.from().length() + edge.to().length() + edge.provenance().file().length()) / 4 + 6;
        }
        return tokens;
    }

    private static boolean touches(GraphEdge edge, String subjectId) {
        return owner(edge.from()).equals(subjectId) || owner(edge.to()).equals(subjectId)
                || edge.from().equals(subjectId) || edge.to().equals(subjectId);
    }

    private static String owner(String id) {
        int member = id.indexOf('#');
        if (member > 0) return id.substring(0, member);
        int field = id.indexOf(".field:");
        return field > 0 ? id.substring(0, field) : id;
    }

    private static int length(GraphNode node) { return (node.id().length() + node.name().length()) / 4 + 3; }

    private static boolean isAnchorable(GraphNode node) {
        return node.kind() != EntityKind.FILE && node.kind() != EntityKind.PACKAGE && node.kind() != EntityKind.REPOSITORY;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }

    /** The most symbol-like word in the question: a dotted name, or the longest capitalized token. */
    static String subjectOf(String question) {
        String best = "";
        for (String word : question.split("[\\s,?()]+")) {
            String cleaned = word.replaceAll("[^A-Za-z0-9_.#/()]", "");
            if (cleaned.isBlank()) continue;
            boolean symbolic = cleaned.contains(".") || cleaned.contains("#")
                    || (Character.isUpperCase(cleaned.charAt(0)) && !cleaned.equals(cleaned.toUpperCase(Locale.ROOT)));
            if (symbolic && cleaned.length() > best.length()) best = cleaned;
        }
        return best;
    }
}

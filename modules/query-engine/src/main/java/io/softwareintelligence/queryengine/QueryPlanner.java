package io.softwareintelligence.queryengine;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.ContextPacket;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.GraphQueries;
import io.softwareintelligence.indextree.IndexTree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /**
     * Everything an answer may cite.
     *
     * <p>{@code subject} and {@code context} are the primary anchor and its packet, which is all a
     * single-anchor question needs. {@code anchors} and {@code contexts} carry the rest: a question
     * with a plural answer - which endpoints touch this table, what does this module depend on -
     * has no single subject, and narrowing to one was how that class of question used to become
     * unanswerable before it was ever traversed.
     */
    public record Answerable(Plan plan, Optional<GraphNode> subject, List<Bm25Index.Hit> retrieved,
                             Optional<ContextPacket> context, List<GraphNode> anchors,
                             List<ContextPacket> contexts, RetrievalMode retrieval,
                             Optional<TreeNavigator.Descent> descent) {

        public Set<String> anchorIds() {
            Set<String> ids = new LinkedHashSet<>();
            anchors.forEach(node -> ids.add(node.id()));
            return ids;
        }

        /** One packet covering every anchor, for a reader that wants the whole answer at once. */
        public Optional<ContextPacket> merged() {
            return contexts.isEmpty() ? Optional.empty() : Optional.of(merge(contexts));
        }
    }

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

    /** Runs the plan with flat retrieval and a single anchor: the behaviour that predates the tree. */
    public static Answerable plan(CodeGraph graph, Bm25Index index, String question, int retrievalLimit) {
        return plan(graph, index, null, question, retrievalLimit, RetrievalMode.BM25, 1, DEFAULT_BEAM);
    }

    /**
     * Runs the plan and returns everything an answer may cite — and nothing else.
     *
     * <p>The retrieval mode decides only <em>where the anchors come from</em>. Everything after
     * anchoring — traversal, packets, evidence, verification — is identical in all three modes,
     * which is what makes a comparison between them a measurement of retrieval rather than of two
     * different products.
     */
    public static Answerable plan(CodeGraph graph, Bm25Index index, IndexTree tree, String question,
                                  int retrievalLimit, RetrievalMode mode, int maxAnchors, int beam) {
        Plan plan = classify(question);
        List<Bm25Index.Hit> hits = index.search(question, retrievalLimit);
        if (mode.needsTree() && tree == null) {
            throw new IllegalArgumentException("retrieval mode " + mode + " needs an index tree; build one with `repo-intel index`");
        }

        Optional<TreeNavigator.Descent> descent = mode.needsTree()
                ? Optional.of(TreeNavigator.descend(tree, question, plan, beam, Math.max(1, maxAnchors)))
                : Optional.empty();

        List<GraphNode> anchors = new ArrayList<>();
        // A symbol the question names by hand is proof, not a guess, so it leads wherever the flat
        // path is in play. Ranking a descent's opinion above it cost this a question on the fixture:
        // "which endpoint does PaymentService serve" descended towards endpoints and pushed
        // PaymentService itself to third.
        if (mode.needsFlat()) GraphQueries.findSymbol(graph, plan.subject()).ifPresent(node -> add(anchors, node));
        // Then the descent, and the flat hits behind it: in HYBRID those are the escape hatch for a
        // descent that took a wrong branch at the first level.
        descent.ifPresent(found -> found.anchorGraphIds().stream()
                .map(graph::node).flatMap(Optional::stream).forEach(node -> add(anchors, node)));
        if (mode.needsFlat()) {
            hits.stream().map(Bm25Index.Hit::node).filter(QueryPlanner::isAnchorable).forEach(node -> add(anchors, node));
        }

        List<GraphNode> selected = anchors.stream().limit(Math.max(1, maxAnchors)).toList();
        List<ContextPacket> contexts = selected.stream()
                .map(node -> GraphQueries.context(graph, node, plan.depth())).toList();
        return new Answerable(plan, selected.stream().findFirst(), hits, contexts.stream().findFirst(),
                selected, contexts, mode, descent);
    }

    /** How wide a tree descent keeps its frontier when a caller does not say. */
    public static final int DEFAULT_BEAM = 4;

    private static void add(List<GraphNode> anchors, GraphNode node) {
        if (anchors.stream().noneMatch(existing -> existing.id().equals(node.id()))) anchors.add(node);
    }

    /**
     * Merges per-anchor packets into one. The first anchor stays the packet's subject because a
     * packet has exactly one, and every anchor's evidence is preserved, so a claim about a
     * secondary anchor still has its citation in the packet that carries it.
     */
    public static ContextPacket merge(List<ContextPacket> packets) {
        if (packets.size() == 1) return packets.get(0);
        List<GraphNode> callers = new ArrayList<>();
        List<GraphNode> endpoints = new ArrayList<>();
        List<GraphNode> dependencies = new ArrayList<>();
        Map<String, GraphEdge> evidence = new LinkedHashMap<>();
        for (ContextPacket packet : packets) {
            packet.callers().forEach(node -> addNode(callers, node));
            packet.endpoints().forEach(node -> addNode(endpoints, node));
            packet.dependencies().forEach(node -> addNode(dependencies, node));
            packet.evidence().forEach(edge -> evidence.put(key(edge), edge));
        }
        return new ContextPacket(packets.get(0).subject(), List.copyOf(callers), List.copyOf(endpoints),
                List.copyOf(dependencies), List.copyOf(evidence.values()));
    }

    private static void addNode(List<GraphNode> into, GraphNode node) {
        if (into.stream().noneMatch(existing -> existing.id().equals(node.id()))) into.add(node);
    }

    private static String key(GraphEdge edge) {
        return edge.from() + '|' + edge.to() + '|' + edge.kind() + '|' + edge.provenance().line() + '|' + edge.provenance().column();
    }

    /**
     * Compresses a packet to a token budget, dropping the least load-bearing evidence first.
     * Endpoints and direct callers survive longest because they carry the operational answer.
     */
    public static ContextPacket compress(ContextPacket packet, int tokenBudget) {
        return compress(packet, Set.of(packet.subject().id()), tokenBudget);
    }

    /**
     * Compresses a packet whose evidence supports several anchors.
     *
     * <p>Every anchor's edges are equally load-bearing, so they share the front of the sort rather
     * than the first anchor's edges crowding the rest out — which is what a single-subject sort
     * would do to a merged packet, quietly turning a plural answer back into a singular one.
     */
    public static ContextPacket compress(ContextPacket packet, Set<String> anchorIds, int tokenBudget) {
        if (estimateTokens(packet) <= tokenBudget) return packet;
        List<GraphNode> callers = new ArrayList<>(packet.callers());
        List<GraphEdge> evidence = new ArrayList<>(packet.evidence());
        Set<String> keep = new LinkedHashSet<>();
        packet.endpoints().forEach(node -> keep.add(node.id()));
        packet.dependencies().forEach(node -> keep.add(node.id()));

        // Edges that touch an anchor rank first. On a hub symbol the packet holds thousands of
        // equal-confidence edges, and dropping from the end of a confidence-only sort leaves
        // whichever unrelated path edge happened to sort last - an answer about the wrong symbol.
        Set<String> subjects = anchorIds.isEmpty() ? Set.of(packet.subject().id()) : anchorIds;
        evidence.sort(Comparator.comparingInt((GraphEdge edge) -> touches(edge, subjects) ? 0 : 1)
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

    private static boolean touches(GraphEdge edge, Set<String> subjectIds) {
        return subjectIds.contains(edge.from()) || subjectIds.contains(edge.to())
                || subjectIds.contains(owner(edge.from())) || subjectIds.contains(owner(edge.to()));
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

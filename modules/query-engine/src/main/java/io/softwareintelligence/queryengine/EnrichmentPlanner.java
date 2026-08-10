package io.softwareintelligence.queryengine;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.RelationKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Decides which symbols would be worth spending model tokens on, and stops at a budget. It ranks
 * candidates but never calls anything: the product's invariant is that the deterministic model is
 * complete without enrichment, so this plans an optional step rather than performing a required one.
 *
 * <p>Ranking rewards symbols that are central, ambiguous, and operationally exposed, and penalizes
 * ones that are already well described by deterministic facts. Every decision is returned with its
 * score and its reason, so an audit can answer "why did we pay for this symbol".
 */
public final class EnrichmentPlanner {
    public record Candidate(String id, double score, int estimatedTokens, String reason) { }

    public record Budget(int maxTokens, double costPerThousandTokens) {
        public double estimatedCost(int tokens) { return costPerThousandTokens * tokens / 1000.0; }
    }

    public record Plan(List<Candidate> selected, List<Candidate> skipped, int plannedTokens, double estimatedCost) { }

    private EnrichmentPlanner() { }

    public static Plan plan(CodeGraph graph, Budget budget) {
        List<Candidate> ranked = rank(graph);
        List<Candidate> selected = new ArrayList<>();
        List<Candidate> skipped = new ArrayList<>();
        int spent = 0;
        for (Candidate candidate : ranked) {
            if (spent + candidate.estimatedTokens() <= budget.maxTokens()) {
                selected.add(candidate);
                spent += candidate.estimatedTokens();
            } else {
                skipped.add(candidate);
            }
        }
        return new Plan(List.copyOf(selected), List.copyOf(skipped), spent, budget.estimatedCost(spent));
    }

    static List<Candidate> rank(CodeGraph graph) {
        List<Candidate> candidates = new ArrayList<>();
        for (GraphNode node : graph.nodes()) {
            if (node.kind() == EntityKind.EXTERNAL_SYMBOL || node.kind() == EntityKind.FILE
                    || node.kind() == EntityKind.PACKAGE || node.kind() == EntityKind.REPOSITORY) continue;
            // A library method called from this repository is recorded as a METHOD, not as an
            // EXTERNAL_SYMBOL, so kind alone does not tell them apart. Without this, the top
            // enrichment candidate for jackson-databind is JUnit's assertEquals with 5,979
            // references: a whole token budget spent describing someone else's library.
            if (!isDeclaredHere(graph, node)) continue;
            List<GraphEdge> incoming = graph.incoming(node.id());
            List<GraphEdge> outgoing = graph.outgoing(node.id());
            int downstream = incoming.size();
            long ambiguous = incoming.stream().filter(edge -> edge.provenance().confidence() < 0.95).count()
                    + outgoing.stream().filter(edge -> edge.provenance().confidence() < 0.95).count();
            boolean exposed = outgoing.stream().anyMatch(edge -> edge.kind() == RelationKind.PERSISTS || edge.kind() == RelationKind.PUBLISHES)
                    || incoming.stream().anyMatch(edge -> edge.kind() == RelationKind.EXPOSES);
            boolean described = node.attributes().containsKey("annotations") && !node.attributes().get("annotations").isBlank();

            double score = downstream * 1.0 + ambiguous * 2.5 + (exposed ? 8.0 : 0.0) - (described ? 3.0 : 0.0);
            if (score <= 0) continue;
            String reason = String.format("%d references, %d low-confidence edges%s%s",
                    downstream, ambiguous, exposed ? ", operationally exposed" : "", described ? ", already annotated" : "");
            candidates.add(new Candidate(node.id(), Math.round(score * 100.0) / 100.0, estimateTokens(node), reason));
        }
        return candidates.stream()
                .sorted(Comparator.comparingDouble(Candidate::score).reversed().thenComparing(Candidate::id))
                .toList();
    }

    /**
     * True when this repository declares the symbol, rather than merely calling it.
     *
     * <p>Derived facts such as modules, workflows, and capabilities have no declaring edge but are
     * this repository's own, so they qualify too.
     */
    private static boolean isDeclaredHere(CodeGraph graph, GraphNode node) {
        return switch (node.kind()) {
            case MODULE, WORKFLOW, BUSINESS_CAPABILITY, ENDPOINT, TOPIC, DATABASE_TABLE,
                 SECURITY_GUARD, EXTERNAL_SERVICE, CONFIGURATION_PROPERTY, TRANSACTION -> true;
            default -> graph.incoming(node.id()).stream()
                    .anyMatch(edge -> edge.kind() == RelationKind.DECLARES || edge.kind() == RelationKind.CONTAINS);
        };
    }

    /** A stable estimate of the prompt cost of describing one symbol, in tokens. */
    private static int estimateTokens(GraphNode node) {
        int attributes = node.attributes().entrySet().stream()
                .mapToInt(entry -> (entry.getKey().length() + entry.getValue().length()) / 4).sum();
        return 120 + node.id().length() / 4 + attributes;
    }

    public static String audit(Plan plan, Budget budget) {
        StringBuilder text = new StringBuilder(String.format(
                "ENRICHMENT PLAN: %d selected, %d skipped, %d/%d tokens, estimated cost %.4f%n",
                plan.selected().size(), plan.skipped().size(), plan.plannedTokens(), budget.maxTokens(), plan.estimatedCost()));
        for (Candidate candidate : plan.selected()) {
            text.append(String.format("  SELECT %-60s score=%-7s tokens=%-5d %s%n",
                    candidate.id(), candidate.score(), candidate.estimatedTokens(), candidate.reason()));
        }
        if (!plan.skipped().isEmpty()) {
            text.append(String.format("  (%d candidates skipped for budget; highest skipped score %s)%n",
                    plan.skipped().size(), plan.skipped().get(0).score()));
        }
        return text.toString();
    }

    /** The contract an optional model-backed enricher must satisfy. No implementation ships. */
    public interface SemanticEnricher {
        /** Returns claims for one symbol; each must cite graph relationships to survive verification. */
        List<VerifiedAnswer.Claim> describe(GraphNode node, Map<String, String> context);
    }
}

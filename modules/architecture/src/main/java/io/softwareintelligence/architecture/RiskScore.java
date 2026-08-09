package io.softwareintelligence.architecture;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.ImpactReport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An explainable change-risk score. Every point is attributable to a named, source-backed factor,
 * and the factors are returned with the score so a reviewer can disagree with the weighting rather
 * than with an opaque number.
 *
 * <p>No model is involved. The score is a weighted sum of counts the graph already proves: how many
 * callers a change reaches, how many HTTP endpoints and workflows sit downstream, whether it
 * crosses a transaction or a persistence boundary, and how weak the weakest link in the evidence
 * chain is. Confidence is a discount, not a bonus: an impact proven only by 0.6-confidence dispatch
 * edges scores lower than the same impact proven by compiler bindings.
 */
public final class RiskScore {
    public record Factor(String name, int count, double weight, double contribution, String explanation) { }

    public record Assessment(String subject, double score, String band, List<Factor> factors) { }

    private static final double ENDPOINT_WEIGHT = 6.0;
    private static final double TRANSITIVE_WEIGHT = 0.5;
    private static final double DIRECT_WEIGHT = 1.0;
    private static final double PERSISTENCE_WEIGHT = 4.0;
    private static final double TRANSACTION_WEIGHT = 3.0;
    private static final double EVENT_WEIGHT = 4.0;
    private static final double EXTERNAL_SERVICE_WEIGHT = 5.0;
    private static final double GUARD_WEIGHT = 5.0;

    private RiskScore() { }

    public static Assessment assess(CodeGraph graph, ImpactReport report) {
        List<ImpactReport.ImpactPath> all = new ArrayList<>(report.direct());
        all.addAll(report.transitive());
        Map<EntityKind, Integer> byKind = new LinkedHashMap<>();
        for (ImpactReport.ImpactPath path : all) byKind.merge(path.target().kind(), 1, Integer::sum);

        List<Factor> factors = new ArrayList<>();
        factors.add(factor("direct-callers", report.direct().size(), DIRECT_WEIGHT, "code that references the change directly"));
        factors.add(factor("transitive-callers", report.transitive().size(), TRANSITIVE_WEIGHT, "code reached through one or more hops"));
        factors.add(factor("exposed-endpoints", byKind.getOrDefault(EntityKind.ENDPOINT, 0), ENDPOINT_WEIGHT, "HTTP routes whose behaviour can change"));
        factors.add(factor("database-tables", byKind.getOrDefault(EntityKind.DATABASE_TABLE, 0), PERSISTENCE_WEIGHT, "tables reachable from the change"));
        factors.add(factor("transaction-boundaries", byKind.getOrDefault(EntityKind.TRANSACTION, 0), TRANSACTION_WEIGHT, "transactional scopes involved"));
        factors.add(factor("topics", byKind.getOrDefault(EntityKind.TOPIC, 0), EVENT_WEIGHT, "message topics produced or consumed"));
        factors.add(factor("external-services", byKind.getOrDefault(EntityKind.EXTERNAL_SERVICE, 0), EXTERNAL_SERVICE_WEIGHT, "outbound service dependencies"));
        factors.add(factor("security-guards", byKind.getOrDefault(EntityKind.SECURITY_GUARD, 0), GUARD_WEIGHT, "authorization rules attached to affected code"));
        factors.add(factor("workflows", byKind.getOrDefault(EntityKind.WORKFLOW, 0), ENDPOINT_WEIGHT, "end-to-end flows that traverse the change"));

        double raw = factors.stream().mapToDouble(Factor::contribution).sum();
        double weakestEvidence = all.stream()
                .flatMap(path -> path.evidence().stream())
                .mapToDouble(edge -> edge.provenance().confidence())
                .min().orElse(1.0);
        double score = Math.round(raw * weakestEvidence * 100.0) / 100.0;
        factors.add(new Factor("evidence-confidence", all.size(), weakestEvidence, 0.0,
                "score scaled by the weakest confidence in the supporting evidence (" + weakestEvidence + ")"));

        return new Assessment(report.subject().id(), score, band(score),
                factors.stream().filter(factor -> factor.count() > 0 || factor.name().equals("evidence-confidence"))
                        .sorted(Comparator.comparingDouble(Factor::contribution).reversed()).toList());
    }

    private static Factor factor(String name, int count, double weight, String explanation) {
        return new Factor(name, count, weight, count * weight, explanation);
    }

    private static String band(double score) {
        if (score >= 60) return "HIGH";
        if (score >= 20) return "MEDIUM";
        if (score > 0) return "LOW";
        return "NONE";
    }

    /** A textual explanation suitable for a build log or a pull-request comment. */
    public static String explain(Assessment assessment) {
        StringBuilder text = new StringBuilder("RISK ").append(assessment.band()).append(" (").append(assessment.score())
                .append(") for ").append(assessment.subject()).append('\n');
        for (Factor factor : assessment.factors()) {
            text.append(String.format("  %-24s count=%-4d weight=%-5s -> %-6s %s%n",
                    factor.name(), factor.count(), factor.weight(), factor.contribution(), factor.explanation()));
        }
        return text.toString();
    }

    static boolean isEntryPoint(GraphNode node) {
        return node.kind() == EntityKind.ENDPOINT || node.kind() == EntityKind.TOPIC;
    }
}

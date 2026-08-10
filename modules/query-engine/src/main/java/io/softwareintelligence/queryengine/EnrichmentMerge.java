package io.softwareintelligence.queryengine;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.Provenance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies verified claims to a graph, under rules that keep the deterministic model intact.
 *
 * <ol>
 *   <li>A claim must pass {@link VerifiedAnswer} first: its citations must exist and must involve
 *       the symbol it is about.</li>
 *   <li>A claim becomes a {@code claim.*} attribute. It never replaces a node's name, kind, or
 *       provenance, and it never creates an edge — a model may describe the graph, not extend it.</li>
 *   <li>Confidence is capped below every deterministic tier, so an enriched fact can never
 *       out-rank a compiler binding when a query applies a confidence floor.</li>
 *   <li>A DISPUTE is never applied. It is collected for human review.</li>
 * </ol>
 *
 * <p>Because claims live in a pinned file, applying them is a pure function of (graph, claims file).
 * The enriched graph is therefore as reproducible as the deterministic one, even though the process
 * that authored the claims was not.
 */
public final class EnrichmentMerge {

    public record Rejected(EnrichmentClaims.Claim claim, String reason) { }

    public record Result(int applied, List<Rejected> rejected, List<EnrichmentClaims.Claim> disputes) {
        public boolean clean() { return rejected.isEmpty() && disputes.isEmpty(); }
    }

    private EnrichmentMerge() { }

    public static Result apply(CodeGraph graph, List<EnrichmentClaims.Claim> claims) {
        List<Rejected> rejected = new ArrayList<>();
        List<EnrichmentClaims.Claim> disputes = new ArrayList<>();
        int applied = 0;

        for (EnrichmentClaims.Claim claim : claims) {
            if (claim.kind() == EnrichmentClaims.ClaimKind.DISPUTE) {
                disputes.add(claim);
                continue;
            }
            if (claim.value() == null || claim.value().isBlank()) {
                rejected.add(new Rejected(claim, "empty value"));
                continue;
            }
            GraphNode node = graph.node(claim.subject()).orElse(null);
            if (node == null) {
                rejected.add(new Rejected(claim, "subject is not a symbol in this graph"));
                continue;
            }
            VerifiedAnswer.Answer verdict = VerifiedAnswer.verify(graph, "enrichment",
                    List.of(new VerifiedAnswer.Claim(claim.value(), claim.subject(), claim.citations())));
            VerifiedAnswer.VerifiedClaim checked = verdict.claims().get(0);
            if (!checked.supported()) {
                rejected.add(new Rejected(claim, checked.verdict() + ": " + checked.explanation()));
                continue;
            }
            Map<String, String> attributes = new LinkedHashMap<>(node.attributes());
            String key = "claim." + claim.kind().name().toLowerCase(java.util.Locale.ROOT);
            attributes.put(key, claim.value());
            attributes.put(key + ".model", claim.model());
            attributes.put(key + ".confidence", Double.toString(Math.min(claim.confidence(), EnrichmentClaims.MAX_CLAIM_CONFIDENCE)));
            // The node keeps its own kind, name, and provenance. Only attributes are added, so a
            // graph can be stripped back to deterministic facts by dropping every claim.* key.
            graph.upsertNode(new GraphNode(node.id(), node.kind(), node.name(), Map.copyOf(attributes), node.provenance()), true);
            applied++;
        }
        return new Result(applied, List.copyOf(rejected), List.copyOf(disputes));
    }

    /** Removes every enriched attribute, returning the graph to what the compiler and rules proved. */
    public static int strip(CodeGraph graph) {
        int stripped = 0;
        for (GraphNode node : List.copyOf(graph.nodes())) {
            if (node.attributes().keySet().stream().noneMatch(key -> key.startsWith("claim."))) continue;
            Map<String, String> attributes = new LinkedHashMap<>();
            node.attributes().forEach((key, value) -> { if (!key.startsWith("claim.")) attributes.put(key, value); });
            graph.upsertNode(new GraphNode(node.id(), node.kind(), node.name(), Map.copyOf(attributes), node.provenance()), true);
            stripped++;
        }
        return stripped;
    }

    public static String report(Result result) {
        StringBuilder text = new StringBuilder(String.format(
                "ENRICHMENT: %d applied, %d rejected, %d disputes%n", result.applied(), result.rejected().size(), result.disputes().size()));
        for (Rejected rejected : result.rejected()) {
            text.append("  REJECTED ").append(rejected.claim().subject()).append(" [").append(rejected.claim().kind())
                    .append("]: ").append(rejected.reason()).append('\n');
        }
        for (EnrichmentClaims.Claim dispute : result.disputes()) {
            text.append("  DISPUTE  ").append(dispute.subject()).append(": ").append(dispute.value())
                    .append("\n           (never applied; the deterministic model stands until a human rules on it)\n");
        }
        return text.toString();
    }

    /** Provenance for anything an enricher authored, always below every deterministic tier. */
    static Provenance provenance(EnrichmentClaims.Claim claim, Provenance origin) {
        return new Provenance(EnrichmentClaims.RESOLVER, Math.min(claim.confidence(), EnrichmentClaims.MAX_CLAIM_CONFIDENCE),
                origin.file(), origin.line(), origin.column());
    }
}

package io.softwareintelligence.queryengine;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.ContextPacket;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Verifies claims against the graph before they are shown. A claim must name the symbol it is about
 * and cite at least one edge; the citation is then checked to exist, to actually touch that symbol,
 * and to carry a source location. Anything that fails is labelled UNSUPPORTED rather than deleted,
 * so a reviewer can see what was asserted and why it was rejected.
 *
 * <p>Nothing here generates text or calls a model. Generation is a separate, optional step; this
 * class is the gate it must pass through, and it works identically whether the claim came from a
 * model, a rule, or a human.
 */
public final class VerifiedAnswer {
    public enum Verdict { SUPPORTED, UNSUPPORTED_MISSING_CITATION, UNSUPPORTED_UNKNOWN_SYMBOL, UNSUPPORTED_CITATION_NOT_IN_GRAPH, UNSUPPORTED_CITATION_UNRELATED }

    /** A claim as produced by any generator: a statement, the symbol it is about, and its citations. */
    public record Claim(String statement, String subjectId, List<Citation> citations) { }

    public record Citation(String from, String to, String kind) { }

    public record VerifiedClaim(Claim claim, Verdict verdict, List<GraphEdge> evidence, String explanation) {
        public boolean supported() { return verdict == Verdict.SUPPORTED; }
    }

    public record Answer(String question, List<VerifiedClaim> claims) {
        public List<VerifiedClaim> supported() { return claims.stream().filter(VerifiedClaim::supported).toList(); }
        public List<VerifiedClaim> withheld() { return claims.stream().filter(claim -> !claim.supported()).toList(); }
    }

    private VerifiedAnswer() { }

    public static Answer verify(CodeGraph graph, String question, List<Claim> claims) {
        List<VerifiedClaim> verified = new ArrayList<>();
        for (Claim claim : claims) verified.add(verify(graph, claim));
        return new Answer(question, List.copyOf(verified));
    }

    private static VerifiedClaim verify(CodeGraph graph, Claim claim) {
        if (claim.citations().isEmpty()) {
            return new VerifiedClaim(claim, Verdict.UNSUPPORTED_MISSING_CITATION, List.of(),
                    "the claim cites no relationship, so nothing in the repository supports it");
        }
        Optional<GraphNode> subject = graph.node(claim.subjectId());
        if (subject.isEmpty()) {
            return new VerifiedClaim(claim, Verdict.UNSUPPORTED_UNKNOWN_SYMBOL, List.of(),
                    "the claim is about '" + claim.subjectId() + "', which is not a symbol in this repository");
        }
        List<GraphEdge> evidence = new ArrayList<>();
        for (Citation citation : claim.citations()) {
            Optional<GraphEdge> match = graph.outgoing(citation.from()).stream()
                    .filter(edge -> edge.to().equals(citation.to()) && edge.kind().name().equals(citation.kind()))
                    .findFirst();
            if (match.isEmpty()) {
                return new VerifiedClaim(claim, Verdict.UNSUPPORTED_CITATION_NOT_IN_GRAPH, List.copyOf(evidence),
                        "cited relationship " + citation.from() + " -" + citation.kind() + "-> " + citation.to() + " does not exist");
            }
            evidence.add(match.get());
        }
        boolean touchesSubject = evidence.stream().anyMatch(edge ->
                edge.from().equals(claim.subjectId()) || edge.to().equals(claim.subjectId())
                        || owner(edge.from()).equals(claim.subjectId()) || owner(edge.to()).equals(claim.subjectId()));
        if (!touchesSubject) {
            return new VerifiedClaim(claim, Verdict.UNSUPPORTED_CITATION_UNRELATED, List.copyOf(evidence),
                    "every cited relationship exists, but none of them involves " + claim.subjectId());
        }
        return new VerifiedClaim(claim, Verdict.SUPPORTED, List.copyOf(evidence),
                "supported by " + evidence.size() + " relationship(s) with source evidence");
    }

    /**
     * Restates a packet as claims, which is the deterministic path that needs no generator at all.
     *
     * <p>Each claim is anchored on a symbol the cited edge actually touches — not on the packet's
     * subject. A packet legitimately contains edges one hop away from the subject, and asserting
     * those are "about" the subject would be exactly the unsupported attribution this class exists
     * to catch.
     */
    public static List<Claim> claimsFrom(ContextPacket packet) {
        return claimsFrom(packet, java.util.Set.of(packet.subject().id()));
    }

    /**
     * Restates a packet built from several anchors.
     *
     * <p>Each claim is anchored on the anchor its cited edge actually touches, not on the first one:
     * a merged packet legitimately contains evidence for every anchor, and attributing all of it to
     * whichever anchor happened to sort first is the same unsupported attribution this class exists
     * to catch, only harder to notice.
     */
    public static List<Claim> claimsFrom(ContextPacket packet, java.util.Set<String> anchorIds) {
        java.util.Set<String> anchors = anchorIds.isEmpty() ? java.util.Set.of(packet.subject().id()) : anchorIds;
        List<Claim> claims = new ArrayList<>();
        for (GraphEdge edge : packet.evidence()) {
            claims.add(new Claim(describe(edge), anchorFor(edge, anchors),
                    List.of(new Citation(edge.from(), edge.to(), edge.kind().name()))));
        }
        return List.copyOf(claims);
    }

    /** The anchor an edge belongs to, preferring the end that owns it, and falling back to its source. */
    private static String anchorFor(GraphEdge edge, java.util.Set<String> anchors) {
        for (String candidate : List.of(edge.from(), edge.to(), owner(edge.from()), owner(edge.to()))) {
            if (anchors.contains(candidate)) return candidate;
        }
        return edge.from();
    }

    public static String render(Answer answer) {
        StringBuilder text = new StringBuilder("QUESTION: ").append(answer.question()).append('\n');
        text.append("SUPPORTED CLAIMS (").append(answer.supported().size()).append(")\n");
        for (VerifiedClaim claim : answer.supported()) {
            text.append("  - ").append(claim.claim().statement()).append('\n');
            for (GraphEdge edge : claim.evidence()) {
                text.append("      evidence: ").append(edge.provenance().file()).append(':').append(edge.provenance().line())
                        .append(" [").append(edge.provenance().resolver()).append(' ')
                        .append(String.format("%.2f", edge.provenance().confidence())).append("]\n");
            }
        }
        if (!answer.withheld().isEmpty()) {
            text.append("WITHHELD CLAIMS (").append(answer.withheld().size()).append(")\n");
            for (VerifiedClaim claim : answer.withheld()) {
                text.append("  - ").append(claim.claim().statement()).append("\n      ")
                        .append(claim.verdict()).append(": ").append(claim.explanation()).append('\n');
            }
        }
        return text.toString();
    }

    private static String describe(GraphEdge edge) {
        return edge.from() + " " + edge.kind().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + " " + edge.to();
    }

    private static String owner(String id) {
        int member = id.indexOf('#');
        if (member > 0) return id.substring(0, member);
        int field = id.indexOf(".field:");
        return field > 0 ? id.substring(0, field) : id;
    }
}

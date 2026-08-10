package io.softwareintelligence.queryengine;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The file contract between a semantic enricher and the graph.
 *
 * <p>An enricher — a model, an agent fleet, or a human — reads work packets and writes claims. It
 * never writes to the graph. Claims are stored as a file that is committed next to the source, so
 * the enriched graph is <em>deterministic graph + pinned claims</em>: reproducible even though the
 * process that produced the claims was not. Re-running the enricher produces a new file that can be
 * diffed against the old one, which is the only honest way to version a model's opinion.
 *
 * <p>Claims can add meaning. They cannot correct evidence. A claim that contradicts a compiler
 * binding is recorded as a review item for a human, because between Eclipse JDT and a language
 * model on the question of what a Java program does, JDT wins.
 */
public final class EnrichmentClaims {

    /** What an enricher is allowed to assert. */
    public enum ClaimKind {
        /** A domain name for something the code names structurally, e.g. capability "owners". */
        NAME,
        /** A short description of what a symbol is for. */
        SUMMARY,
        /** A business rule or invariant the code enforces, stated in domain terms. */
        RULE,
        /** A disagreement with the deterministic model. Never applied; always surfaced for review. */
        DISPUTE
    }

    public record Claim(String subject, ClaimKind kind, String value, List<VerifiedAnswer.Citation> citations,
                        String model, double confidence) { }

    /** The highest confidence any model-authored claim may carry. */
    public static final double MAX_CLAIM_CONFIDENCE = 0.80;
    public static final String RESOLVER = "SEMANTIC_ENRICHMENT";

    private EnrichmentClaims() { }

    /**
     * A self-contained unit of work for one agent: the symbol, its evidence, and the exact
     * relationships it is allowed to cite. An agent that cannot see the whole graph cannot
     * hallucinate about parts of it that were never in front of it.
     */
    public record WorkPacket(String subject, String kind, String name, Map<String, String> facts,
                             List<String> evidence, List<String> citableRelationships) { }

    public static List<WorkPacket> workPackets(CodeGraph graph, List<EnrichmentPlanner.Candidate> candidates) {
        List<WorkPacket> packets = new ArrayList<>();
        for (EnrichmentPlanner.Candidate candidate : candidates) {
            GraphNode node = graph.node(candidate.id()).orElse(null);
            if (node == null) continue;
            List<String> evidence = new ArrayList<>();
            List<String> citable = new ArrayList<>();
            for (GraphEdge edge : graph.outgoing(node.id())) {
                evidence.add(describe(edge, "->"));
                citable.add(edge.from() + " | " + edge.to() + " | " + edge.kind());
            }
            for (GraphEdge edge : graph.incoming(node.id())) {
                evidence.add(describe(edge, "<-"));
                citable.add(edge.from() + " | " + edge.to() + " | " + edge.kind());
            }
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("declaredAt", node.provenance().file() + ":" + node.provenance().line());
            node.attributes().keySet().stream().sorted().forEach(key -> facts.put(key, node.attributes().get(key)));
            packets.add(new WorkPacket(node.id(), node.kind().name(), node.name(),
                    Map.copyOf(facts), List.copyOf(evidence), List.copyOf(citable)));
        }
        return List.copyOf(packets);
    }

    private static String describe(GraphEdge edge, String arrow) {
        return arrow + " " + edge.kind() + " " + (arrow.equals("->") ? edge.to() : edge.from())
                + " [" + edge.provenance().resolver() + " " + edge.provenance().confidence()
                + " " + edge.provenance().file() + ":" + edge.provenance().line() + "]";
    }

    /** The instruction text an enriching agent is given. Kept here so it is versioned with the format. */
    public static String briefing() {
        return """
                You are enriching a deterministic Java code graph with domain meaning.

                You will be given one symbol, the facts already proven about it, and the exact list of
                relationships you may cite. Rules:

                1. Every claim must cite at least one relationship from the citable list, verbatim.
                2. Never invent a relationship. If the evidence does not support a claim, omit it.
                3. Do not restate structure. "PaymentController calls PaymentService" is already known;
                   "authorizes a card payment before an order is confirmed" is new.
                4. If you believe the deterministic model is wrong, emit a DISPUTE claim explaining
                   why. Do not emit a corrected fact: disputes are reviewed by a human, never applied.
                5. Prefer omission to speculation. An empty result is a valid, useful answer.

                Emit JSON only:
                {"claims":[{"subject":"<id>","kind":"NAME|SUMMARY|RULE|DISPUTE","value":"...",
                            "citations":[{"from":"...","to":"...","kind":"..."}],"confidence":0.0-0.8}]}
                """;
    }

    public static String write(List<Claim> claims) {
        // Sorted, so re-running an enricher produces a file that diffs cleanly against the last one.
        List<Claim> ordered = claims.stream()
                .sorted(java.util.Comparator.comparing(Claim::subject)
                        .thenComparing(claim -> claim.kind().name())
                        .thenComparing(Claim::value))
                .toList();
        StringBuilder json = new StringBuilder("{\n  \"resolver\": \"" + RESOLVER + "\",\n  \"claims\": [");
        for (int i = 0; i < ordered.size(); i++) {
            Claim claim = ordered.get(i);
            if (i > 0) json.append(',');
            json.append("\n    {\"subject\": \"").append(Json.quote(claim.subject()))
                    .append("\", \"kind\": \"").append(claim.kind())
                    .append("\", \"value\": \"").append(Json.quote(claim.value()))
                    .append("\", \"model\": \"").append(Json.quote(claim.model()))
                    .append("\", \"confidence\": ").append(Math.min(claim.confidence(), MAX_CLAIM_CONFIDENCE))
                    .append(", \"citations\": [");
            for (int c = 0; c < claim.citations().size(); c++) {
                VerifiedAnswer.Citation citation = claim.citations().get(c);
                if (c > 0) json.append(", ");
                json.append("{\"from\": \"").append(Json.quote(citation.from()))
                        .append("\", \"to\": \"").append(Json.quote(citation.to()))
                        .append("\", \"kind\": \"").append(Json.quote(citation.kind())).append("\"}");
            }
            json.append("]}");
        }
        return json.append("\n  ]\n}\n").toString();
    }

    /** Parses a claims file. Unknown fields are ignored; malformed claims are skipped, not guessed at. */
    public static List<Claim> parse(String json) {
        List<Claim> claims = new ArrayList<>();
        for (String block : blocks(json)) {
            String subject = field(block, "subject");
            String kind = field(block, "kind");
            String value = field(block, "value");
            if (subject == null || kind == null || value == null) continue;
            ClaimKind parsed;
            try {
                parsed = ClaimKind.valueOf(kind.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                continue;
            }
            double confidence = 0.5;
            String raw = field(block, "confidence");
            if (raw != null) {
                try { confidence = Double.parseDouble(raw); } catch (NumberFormatException ignored) { }
            }
            String model = field(block, "model");
            claims.add(new Claim(subject, parsed, value, citations(block), model == null ? "unknown" : model,
                    Math.min(confidence, MAX_CLAIM_CONFIDENCE)));
        }
        return List.copyOf(claims);
    }

    private static List<String> blocks(String json) {
        List<String> found = new ArrayList<>();
        int cursor = json.indexOf("\"claims\"");
        if (cursor < 0) return found;
        int depth = 0, start = -1;
        for (int i = cursor; i < json.length(); i++) {
            char character = json.charAt(i);
            if (character == '{') { if (depth++ == 0) start = i; }
            else if (character == '}') { if (--depth == 0 && start >= 0) { found.add(json.substring(start, i + 1)); start = -1; } }
            else if (character == ']' && depth == 0) break;
        }
        return found;
    }

    private static List<VerifiedAnswer.Citation> citations(String block) {
        List<VerifiedAnswer.Citation> citations = new ArrayList<>();
        int cursor = block.indexOf("\"citations\"");
        if (cursor < 0) return citations;
        String tail = block.substring(cursor);
        int depth = 0, start = -1;
        for (int i = 0; i < tail.length(); i++) {
            char character = tail.charAt(i);
            if (character == '{') { if (depth++ == 0) start = i; }
            else if (character == '}') {
                if (--depth == 0 && start >= 0) {
                    String one = tail.substring(start, i + 1);
                    String from = field(one, "from"), to = field(one, "to"), kind = field(one, "kind");
                    if (from != null && to != null && kind != null) citations.add(new VerifiedAnswer.Citation(from, to, kind));
                    start = -1;
                }
            } else if (character == ']' && depth == 0) break;
        }
        return citations;
    }

    private static String field(String block, String name) {
        int key = block.indexOf('"' + name + '"');
        if (key < 0) return null;
        int colon = block.indexOf(':', key);
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < block.length() && Character.isWhitespace(block.charAt(i))) i++;
        if (i >= block.length()) return null;
        if (block.charAt(i) != '"') {
            int end = i;
            while (end < block.length() && ",}\n\r ".indexOf(block.charAt(end)) < 0) end++;
            return block.substring(i, end);
        }
        StringBuilder text = new StringBuilder();
        for (int c = i + 1; c < block.length(); c++) {
            char character = block.charAt(c);
            if (character == '"') break;
            if (character == '\\' && c + 1 < block.length()) {
                char next = block.charAt(++c);
                text.append(switch (next) { case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r'; default -> next; });
                continue;
            }
            text.append(character);
        }
        return text.toString();
    }
}

package io.softwareintelligence.evaluation;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.ContextPacket;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.GraphQueries;
import io.softwareintelligence.model.ImpactReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Scores the engine against a grounded question set.
 *
 * <p>Three numbers are reported per question and aggregated per run: <em>structural accuracy</em>
 * (did the expected symbols appear), <em>evidence recall</em> (did the expected source locations
 * appear in the evidence), and <em>groundedness</em> (was every returned relationship backed by a
 * source location at or above the question's confidence floor). Latency and packet size are
 * recorded so a quality gain that costs an order of magnitude is visible as such.
 */
public final class EvaluationHarness {

    public record Result(GroundedQuestion question, double structuralAccuracy, double evidenceRecall,
                         double groundedness, long millis, int packetTokens, List<String> missing) {
        public boolean passed() { return structuralAccuracy >= 1.0 && evidenceRecall >= 1.0 && groundedness >= 1.0; }
    }

    public record Report(List<Result> results) {
        public double structuralAccuracy() { return mean(Result::structuralAccuracy); }
        public double evidenceRecall() { return mean(Result::evidenceRecall); }
        public double groundedness() { return mean(Result::groundedness); }
        public long passed() { return results.stream().filter(Result::passed).count(); }
        public long medianMillis() {
            List<Long> sorted = results.stream().map(Result::millis).sorted().toList();
            return sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
        }
        private double mean(java.util.function.ToDoubleFunction<Result> field) {
            return results.isEmpty() ? 0.0 : results.stream().mapToDouble(field).average().orElse(0.0);
        }
    }

    private final int depth;

    public EvaluationHarness(int depth) { this.depth = depth; }

    public static List<GroundedQuestion> load(Path questionSet) throws IOException {
        List<GroundedQuestion> questions = new ArrayList<>();
        for (String line : Files.readAllLines(questionSet, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            questions.add(GroundedQuestion.parse(line));
        }
        return List.copyOf(questions);
    }

    public Report run(CodeGraph graph, List<GroundedQuestion> questions) {
        List<Result> results = new ArrayList<>();
        for (GroundedQuestion question : questions) results.add(score(graph, question));
        return new Report(List.copyOf(results));
    }

    private Result score(CodeGraph graph, GroundedQuestion question) {
        long start = System.nanoTime();
        Optional<GraphNode> subject = GraphQueries.findSymbol(graph, question.subject());
        if (subject.isEmpty()) {
            return new Result(question, 0.0, 0.0, 0.0, elapsed(start), 0, List.of("subject not found: " + question.subject()));
        }
        ImpactReport impact = GraphQueries.impact(graph, subject.get(), depth);
        ContextPacket packet = GraphQueries.context(graph, subject.get(), depth);

        Set<String> returnedNames = new LinkedHashSet<>();
        impact.direct().forEach(path -> returnedNames.add(path.target().id()));
        impact.transitive().forEach(path -> returnedNames.add(path.target().id()));
        packet.callers().forEach(node -> returnedNames.add(node.id()));
        packet.endpoints().forEach(node -> returnedNames.add(node.id()));
        packet.dependencies().forEach(node -> returnedNames.add(node.id()));

        List<GraphEdge> evidence = new ArrayList<>(packet.evidence());
        impact.direct().forEach(path -> evidence.addAll(path.evidence()));
        impact.transitive().forEach(path -> evidence.addAll(path.evidence()));
        Set<String> returnedLocations = new LinkedHashSet<>();
        evidence.forEach(edge -> returnedLocations.add(edge.provenance().file() + ":" + edge.provenance().line()));
        // A symbol the packet cites is a symbol the packet returned; an answer may name it.
        evidence.forEach(edge -> { returnedNames.add(edge.from()); returnedNames.add(edge.to()); });

        List<String> missing = new ArrayList<>();
        int nameHits = 0;
        for (String expected : question.expectedNames()) {
            if (returnedNames.stream().anyMatch(id -> matches(id, expected))) nameHits++;
            else missing.add("name: " + expected);
        }
        int locationHits = 0;
        for (String expected : question.expectedLocations()) {
            if (returnedLocations.stream().anyMatch(location -> location.endsWith(expected))) locationHits++;
            else missing.add("location: " + expected);
        }
        long grounded = evidence.stream()
                .filter(edge -> !edge.provenance().file().isBlank() && edge.provenance().line() > 0)
                .filter(edge -> edge.provenance().confidence() >= question.minimumConfidence())
                .count();

        return new Result(question,
                ratio(nameHits, question.expectedNames().size()),
                ratio(locationHits, question.expectedLocations().size()),
                ratio((int) grounded, evidence.size()),
                elapsed(start), estimateTokens(packet), List.copyOf(missing));
    }

    /** An expectation names a source symbol; an id matches when it ends on that name's boundary. */
    private static boolean matches(String id, String expected) {
        String normalized = expected.toLowerCase(Locale.ROOT);
        String candidate = id.toLowerCase(Locale.ROOT);
        if (candidate.equals(normalized) || candidate.endsWith("." + normalized) || candidate.endsWith(":" + normalized)) return true;
        int hash = candidate.indexOf('#');
        if (hash > 0) {
            String owner = candidate.substring(0, hash);
            String member = candidate.substring(hash + 1);
            String memberName = member.contains("(") ? member.substring(0, member.indexOf('(')) : member;
            if (normalized.contains(".")) {
                String[] parts = normalized.split("\\.");
                String expectedMember = parts[parts.length - 1];
                String expectedOwner = parts[parts.length - 2];
                return memberName.equals(expectedMember) && (owner.endsWith("." + expectedOwner) || owner.endsWith(":" + expectedOwner));
            }
            return memberName.equals(normalized);
        }
        return false;
    }

    private static double ratio(int hits, int total) { return total == 0 ? 1.0 : (double) hits / total; }

    private static long elapsed(long startNanos) { return Math.max(1, (System.nanoTime() - startNanos) / 1_000_000); }

    private static int estimateTokens(ContextPacket packet) {
        int tokens = 0;
        for (GraphEdge edge : packet.evidence()) tokens += (edge.from().length() + edge.to().length()) / 4 + 6;
        return tokens;
    }

    public static String render(Report report) {
        StringBuilder text = new StringBuilder(String.format(
                "EVALUATION: %d/%d questions passed%n  structural accuracy %.3f%n  evidence recall     %.3f%n  groundedness        %.3f%n  median latency      %d ms%n",
                report.passed(), report.results().size(), report.structuralAccuracy(), report.evidenceRecall(),
                report.groundedness(), report.medianMillis()));
        for (Result result : report.results()) {
            if (result.passed()) continue;
            text.append("  FAILED ").append(result.question().id()).append(": ").append(result.question().question()).append('\n');
            result.missing().forEach(missing -> text.append("      missing ").append(missing).append('\n'));
        }
        return text.toString();
    }
}

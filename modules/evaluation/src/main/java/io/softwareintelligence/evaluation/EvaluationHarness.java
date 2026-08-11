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
 * <p>Reported per question and aggregated per run: <em>structural accuracy</em> (recall - did the
 * expected symbols appear), <em>precision</em> (of what came back, how much belonged), <em>evidence
 * recall</em> (did the expected source locations appear), and <em>groundedness</em> (was every
 * returned relationship source-backed at or above the question's confidence floor).
 *
 * <p>Recall alone is not an accuracy measurement, and reporting it as one was this harness's own
 * worst defect: a question expecting two symbols scored 1.000 against an answer containing 7,946.
 * Precision needs ground truth that is complete, which is expensive, so questions declare whether
 * their expected list is exhaustive and only those are scored on it. Every question now reports the
 * size of the answer it was scored against, so an unscored dump is at least a visible one.
 */
public final class EvaluationHarness {

    public record Result(GroundedQuestion question, double structuralAccuracy, double precision,
                         double evidenceRecall, double groundedness, long millis, int packetTokens,
                         int returned, List<String> missing, List<String> unexpected) {
        public boolean passed() {
            boolean recalled = structuralAccuracy >= 1.0 && evidenceRecall >= 1.0 && groundedness >= 1.0;
            return recalled && (!question.exhaustive() || precision >= 1.0);
        }

        /** Precision is only meaningful where the expected list is the complete answer. */
        public boolean precisionScored() { return question.exhaustive(); }
    }

    public record Report(List<Result> results) {
        public double structuralAccuracy() { return mean(Result::structuralAccuracy); }
        public double evidenceRecall() { return mean(Result::evidenceRecall); }
        public double groundedness() { return mean(Result::groundedness); }
        public long passed() { return results.stream().filter(Result::passed).count(); }

        /** Averaged over the questions that can measure it, not over all of them. */
        public double precision() {
            List<Result> scored = results.stream().filter(Result::precisionScored).toList();
            return scored.isEmpty() ? Double.NaN : scored.stream().mapToDouble(Result::precision).average().orElse(0);
        }

        public long precisionScoredCount() { return results.stream().filter(Result::precisionScored).count(); }

        public double medianAnswerSize() {
            List<Integer> sorted = results.stream().map(Result::returned).sorted().toList();
            return sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
        }

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
            return new Result(question, 0.0, 0.0, 0.0, 0.0, elapsed(start), 0, 0,
                    List.of("subject not found: " + question.subject()), List.of());
        }
        ImpactReport impact = GraphQueries.impact(graph, subject.get(), depth);
        ContextPacket packet = GraphQueries.context(graph, subject.get(), depth);

        // The symbols an answer would name, and therefore the population precision is measured over.
        Set<String> answer = new LinkedHashSet<>();
        impact.direct().forEach(path -> answer.add(path.target().id()));
        impact.transitive().forEach(path -> answer.add(path.target().id()));
        packet.callers().forEach(node -> answer.add(node.id()));
        packet.endpoints().forEach(node -> answer.add(node.id()));
        packet.dependencies().forEach(node -> answer.add(node.id()));

        List<GraphEdge> evidence = new ArrayList<>(packet.evidence());
        impact.direct().forEach(path -> evidence.addAll(path.evidence()));
        impact.transitive().forEach(path -> evidence.addAll(path.evidence()));
        Set<String> returnedLocations = new LinkedHashSet<>();
        evidence.forEach(edge -> returnedLocations.add(edge.provenance().file() + ":" + edge.provenance().line()));
        Set<String> cited = new LinkedHashSet<>(answer);
        evidence.forEach(edge -> { cited.add(edge.from()); cited.add(edge.to()); });

        List<String> missing = new ArrayList<>();
        int nameHits = 0;
        for (String expected : question.expectedNames()) {
            if (cited.stream().anyMatch(id -> matches(id, expected))) nameHits++;
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

        // Precision is scored over the impact answer only. A context packet legitimately contains
        // supporting structure that no one would call part of "the answer", and counting it as a
        // false positive would punish the tool for being able to show its working.
        List<String> unexpected = new ArrayList<>();
        double precision = 1.0;
        if (question.exhaustive()) {
            List<String> returnedSymbols = scoredPopulation(question, impact, packet);
            long correct = returnedSymbols.stream()
                    .filter(id -> question.expectedNames().stream().anyMatch(expected -> matches(id, expected))).count();
            returnedSymbols.stream()
                    .filter(id -> question.expectedNames().stream().noneMatch(expected -> matches(id, expected)))
                    .limit(8).forEach(id -> unexpected.add("unexpected: " + id));
            precision = returnedSymbols.isEmpty() ? 1.0 : (double) correct / returnedSymbols.size();
        }

        return new Result(question,
                ratio(nameHits, question.expectedNames().size()),
                precision,
                ratio(locationHits, question.expectedLocations().size()),
                ratio((int) grounded, evidence.size()),
                elapsed(start), estimateTokens(packet), answer.size(),
                List.copyOf(missing), List.copyOf(unexpected));
    }

    /**
     * The set precision is measured over, which must match what the question asked.
     *
     * <p>"What is affected if X changes" is answered by the impact traversal; the packet's
     * dependencies are what X <em>uses</em>, and counting {@code java.lang.String} against the
     * precision of an impact answer measures the wrong thing - as it did the first time this was
     * run.
     */
    private static List<String> scoredPopulation(GroundedQuestion question, ImpactReport impact, ContextPacket packet) {
        Set<String> population = new LinkedHashSet<>();
        switch (question.kind()) {
            case IMPACT -> {
                impact.direct().forEach(path -> population.add(path.target().id()));
                impact.transitive().forEach(path -> population.add(path.target().id()));
            }
            case ENDPOINT -> packet.endpoints().forEach(node -> population.add(node.id()));
            default -> {
                packet.callers().forEach(node -> population.add(node.id()));
                packet.endpoints().forEach(node -> population.add(node.id()));
                packet.dependencies().forEach(node -> population.add(node.id()));
            }
        }
        return List.copyOf(population);
    }

    /** An expectation names a source symbol; an id matches when it ends on that name's boundary. */
    static boolean matches(String id, String expected) {
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
                "EVALUATION: %d/%d questions passed%n  structural accuracy (recall) %.3f%n",
                report.passed(), report.results().size(), report.structuralAccuracy()));
        if (report.precisionScoredCount() > 0) {
            text.append(String.format("  precision                    %.3f  (over %d exhaustive question(s))%n",
                    report.precision(), report.precisionScoredCount()));
        } else {
            text.append("  precision                    not measured: no question declares an exhaustive answer\n");
        }
        text.append(String.format("  evidence recall              %.3f%n  groundedness                 %.3f%n"
                        + "  median answer size           %.0f symbols%n  median latency               %d ms%n",
                report.evidenceRecall(), report.groundedness(), report.medianAnswerSize(), report.medianMillis()));
        for (Result result : report.results()) {
            if (result.passed()) continue;
            text.append("  FAILED ").append(result.question().id()).append(": ").append(result.question().question())
                    .append(" (answer had ").append(result.returned()).append(" symbols)\n");
            result.missing().forEach(missing -> text.append("      missing ").append(missing).append('\n'));
            result.unexpected().forEach(extra -> text.append("      ").append(extra).append('\n'));
        }
        return text.toString();
    }
}

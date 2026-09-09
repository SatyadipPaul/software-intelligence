package io.softwareintelligence.evaluation;

import io.softwareintelligence.indextree.IndexTree;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.GraphQueries;
import io.softwareintelligence.queryengine.Bm25Index;
import io.softwareintelligence.queryengine.QueryPlanner;
import io.softwareintelligence.queryengine.RetrievalMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Scores retrieval on its own, before any traversal happens.
 *
 * <p>{@link EvaluationHarness} looks its subject up by name and measures what the graph does with a
 * perfect anchor. That is the right way to measure traversal and the wrong way to measure
 * retrieval, because retrieval is the step it skips. This harness asks each question as a user
 * would — in words, with no subject supplied — and measures only whether the ranked anchors reached
 * the symbol the question is about.
 *
 * <p>It exists so that a change to retrieval can be falsified. Without it, "the tree improved
 * things" is an opinion.
 */
public final class RetrievalHarness {

    /**
     * One question's retrieval outcome.
     *
     * @param rank 1-based position of the first anchor that reached the subject, or 0 for none
     */
    public record Result(GroundedQuestion question, int rank, int anchors, int cardsRead, long millis,
                         List<String> returned, String subjectId) {
        public boolean found() { return rank > 0; }

        public double reciprocalRank() { return rank == 0 ? 0.0 : 1.0 / rank; }
    }

    public record Report(RetrievalMode mode, List<Result> results, List<GroundedQuestion> unusable) {

        /** How often the right symbol was reached at all, within the anchors returned. */
        public double anchorRecall() { return mean(result -> result.found() ? 1.0 : 0.0); }

        public double recallAt(int k) { return mean(result -> result.found() && result.rank() <= k ? 1.0 : 0.0); }

        public double meanReciprocalRank() { return mean(Result::reciprocalRank); }

        public double medianMillis() {
            List<Long> sorted = results.stream().map(Result::millis).sorted().toList();
            return sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
        }

        public double meanCardsRead() { return mean(result -> result.cardsRead()); }

        private double mean(java.util.function.ToDoubleFunction<Result> field) {
            return results.isEmpty() ? 0.0 : results.stream().mapToDouble(field).average().orElse(0.0);
        }
    }

    private final int retrievalLimit;
    private final int maxAnchors;
    private final int beam;

    public RetrievalHarness(int retrievalLimit, int maxAnchors, int beam) {
        this.retrievalLimit = retrievalLimit;
        this.maxAnchors = maxAnchors;
        this.beam = beam;
    }

    public Report run(CodeGraph graph, IndexTree tree, List<GroundedQuestion> questions, RetrievalMode mode) {
        Bm25Index index = Bm25Index.over(graph);
        List<Result> results = new ArrayList<>();
        List<GroundedQuestion> unusable = new ArrayList<>();
        for (GroundedQuestion question : questions) {
            // A question whose declared subject is not in this graph says nothing about retrieval;
            // counting it as a miss would blame retrieval for a stale question set.
            Optional<GraphNode> subject = GraphQueries.findSymbol(graph, question.subject());
            if (subject.isEmpty()) {
                unusable.add(question);
                continue;
            }
            long start = System.nanoTime();
            QueryPlanner.Answerable answerable = QueryPlanner.plan(graph, index, tree, question.question(),
                    retrievalLimit, mode, maxAnchors, beam);
            long millis = Math.max(1, (System.nanoTime() - start) / 1_000_000);

            List<String> returned = answerable.anchors().stream().map(GraphNode::id).toList();
            int rank = 0;
            for (int i = 0; i < returned.size() && rank == 0; i++) {
                if (reaches(returned.get(i), subject.get().id())) rank = i + 1;
            }
            results.add(new Result(question, rank, returned.size(),
                    answerable.descent().map(descent -> descent.cardsRead()).orElse(0),
                    millis, returned, subject.get().id()));
        }
        return new Report(mode, List.copyOf(results), List.copyOf(unusable));
    }

    /**
     * Whether an anchor reached the symbol the question is about.
     *
     * <p>Landing on a member of the right type counts, and so does landing on the type of the right
     * member: both put the traversal in the right place, and insisting on the exact node would
     * score a difference that makes no difference to the answer.
     */
    static boolean reaches(String anchorId, String subjectId) {
        return anchorId.equals(subjectId) || owner(anchorId).equals(subjectId) || anchorId.equals(owner(subjectId));
    }

    private static String owner(String id) {
        int member = id.indexOf('#');
        if (member > 0) return id.substring(0, member);
        int field = id.indexOf(".field:");
        return field > 0 ? id.substring(0, field) : id;
    }

    public static String render(Report report) {
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
                "RETRIEVAL (%s): %d question(s)%n", report.mode(), report.results().size()));
        text.append(String.format(Locale.ROOT, "  anchor recall                %.3f%n", report.anchorRecall()));
        text.append(String.format(Locale.ROOT, "  recall@1                     %.3f%n", report.recallAt(1)));
        text.append(String.format(Locale.ROOT, "  recall@5                     %.3f%n", report.recallAt(5)));
        text.append(String.format(Locale.ROOT, "  mean reciprocal rank         %.3f%n", report.meanReciprocalRank()));
        text.append(String.format(Locale.ROOT, "  median latency               %.0f ms%n", report.medianMillis()));
        if (report.mode().needsTree()) {
            text.append(String.format(Locale.ROOT, "  cards read per question      %.1f%n", report.meanCardsRead()));
        }
        // Said plainly rather than quietly omitted: with one relevant symbol per question,
        // precision@k is capped at 1/k and would measure the cap, not the ranking.
        text.append("  precision@k                  not reported: one relevant symbol per question makes it 1/k at best\n");
        if (!report.unusable().isEmpty()) {
            text.append(String.format(Locale.ROOT, "  skipped                      %d question(s) whose subject is not in this graph%n",
                    report.unusable().size()));
        }
        for (Result result : report.results()) {
            text.append(String.format(Locale.ROOT, "  %-10s rank %-4s %s%n", result.question().id(),
                    result.found() ? Integer.toString(result.rank()) : "-", result.question().question()));
            if (!result.found()) {
                text.append("               wanted ").append(result.subjectId()).append('\n');
                text.append("               got    ").append(result.returned().isEmpty() ? "(nothing)"
                        : String.join(", ", result.returned().stream().limit(4).toList())).append('\n');
            }
        }
        return text.toString();
    }
}

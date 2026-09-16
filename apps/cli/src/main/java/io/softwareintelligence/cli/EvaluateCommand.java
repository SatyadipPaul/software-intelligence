package io.softwareintelligence.cli;

import io.softwareintelligence.evaluation.EvaluationHarness;
import io.softwareintelligence.evaluation.GroundedQuestion;
import io.softwareintelligence.evaluation.RetrievalHarness;
import io.softwareintelligence.evaluation.TextSearchBaseline;
import io.softwareintelligence.indextree.IndexTree;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.embedding.EncoderFactory;
import io.softwareintelligence.embedding.TextEncoder;
import io.softwareintelligence.queryengine.DenseIndex;
import io.softwareintelligence.queryengine.RetrievalMode;
import io.softwareintelligence.queryengine.EnrichmentPlanner;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "evaluate", description = "Score the engine against a grounded question set.")
final class EvaluateCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Java repository to inspect") private Path repository;
    @CommandLine.Parameters(index = "1", description = "Tab-separated question set") private Path questions;
    @CommandLine.Option(names = "--depth", defaultValue = "4", description = "Traversal depth") private int depth;
    @CommandLine.Option(names = "--fail-under", defaultValue = "1.0", description = "Exit non-zero when mean structural accuracy is below this")
    private double failUnder;

    @CommandLine.Option(names = "--baseline",
            description = "Also score a naive text search over the same questions, so the graph's value is measurable")
    private boolean baseline;

    @CommandLine.Option(names = "--retrieval-only", split = ",",
            description = "Score retrieval instead of traversal, for these modes: ${COMPLETION-CANDIDATES}")
    private List<RetrievalMode> retrievalModes;

    @CommandLine.Option(names = "--anchors", defaultValue = "5",
            description = "How many anchors retrieval scoring asks for")
    private int maxAnchors;

    @CommandLine.Option(names = "--beam", defaultValue = "4", description = "How wide a tree descent keeps its frontier")
    private int beam;

    @CommandLine.Option(names = "--index", description = "Use a pinned tree file rather than deriving one")
    private java.nio.file.Path indexFile;

    @CommandLine.Option(names = "--embedding-model",
            description = "Directory holding model.onnx and vocab.txt, for the DENSE retrieval modes")
    private java.nio.file.Path embeddingModel;

    @CommandLine.Mixin private AnalysisOptions options;

    @Override public Integer call() throws Exception {
        List<GroundedQuestion> set = EvaluationHarness.load(questions);
        CodeGraph graph = options.analyze(repository);
        if (retrievalModes != null && !retrievalModes.isEmpty()) return retrievalOnly(graph, set);

        EvaluationHarness.Report report = new EvaluationHarness(depth).run(graph, set);
        System.out.print(EvaluationHarness.render(report));
        if (baseline) System.out.print(baselineReport(set));
        return report.structuralAccuracy() + 1e-9 < failUnder ? 1 : 0;
    }

    /**
     * Scores anchor selection on its own. The traversal harness looks its subject up by name, which
     * measures what the graph does with a perfect anchor; this asks the question in words, as a user
     * would, and measures only whether retrieval reached the right symbol.
     */
    private int retrievalOnly(CodeGraph graph, List<GroundedQuestion> set) throws Exception {
        IndexTree tree = TreeOptions.load(graph, indexFile);
        RetrievalHarness harness = new RetrievalHarness(10, maxAnchors, beam);
        double best = 0;
        // The encoder is opened once and shared: embedding the anchorable nodes is the whole cost of
        // a dense mode, and doing it per mode would make a comparison of two dense modes a
        // comparison of how many times the same vectors were computed.
        try (TextEncoder encoder = openEncoder()) {
            DenseIndex dense = null;
            if (encoder != null) {
                long start = System.currentTimeMillis();
                dense = DenseIndex.over(graph, encoder);
                System.err.printf("embedded %d nodes in %d ms%n", dense.size(), System.currentTimeMillis() - start);
            }
            for (RetrievalMode mode : retrievalModes) {
                RetrievalHarness.Report report = harness.run(graph, tree, dense, set, mode);
                System.out.print(RetrievalHarness.render(report));
                System.out.println();
                best = Math.max(best, report.anchorRecall());
            }
        }
        return best + 1e-9 < failUnder ? 1 : 0;
    }

    /**
     * Opens the encoder when one is both needed and configured.
     *
     * <p>Null rather than an exception when no dense mode was asked for: a lexical run must not be
     * made to depend on a model being present, which is the local-first invariant in one method.
     */
    private TextEncoder openEncoder() {
        if (retrievalModes.stream().noneMatch(RetrievalMode::needsDense)) return null;
        if (embeddingModel == null) {
            throw new IllegalArgumentException(
                    "a DENSE retrieval mode needs --embedding-model pointing at a directory "
                            + "with model.onnx and vocab.txt");
        }
        return EncoderFactory.open(embeddingModel);
    }

    /** grep, scored by the same rules. "Better than searching for the name" is the claim to beat. */
    private String baselineReport(List<GroundedQuestion> set) throws Exception {
        if (AnalysisOptions.isGraphFile(repository)) {
            return System.lineSeparator() + "BASELINE: skipped, a text search needs the source tree, not a graph file"
                    + System.lineSeparator();
        }
        double recall = 0, evidence = 0, answerSize = 0;
        long millis = 0;
        for (GroundedQuestion question : set) {
            long start = System.nanoTime();
            TextSearchBaseline.Answer answer = TextSearchBaseline.search(repository, question.subject());
            millis += Math.max(1, (System.nanoTime() - start) / 1_000_000);
            recall += TextSearchBaseline.recall(answer, question);
            evidence += TextSearchBaseline.evidenceRecall(answer, question);
            answerSize += answer.symbols().size();
        }
        int count = Math.max(1, set.size());
        return String.format("%nBASELINE (naive text search over the same questions)%n"
                        + "  structural accuracy (recall) %.3f%n  evidence recall              %.3f%n"
                        + "  mean answer size             %.0f symbols%n  mean latency                 %d ms%n",
                recall / count, evidence / count, answerSize / count, millis / count);
    }
}

@CommandLine.Command(name = "enrichment-plan",
        description = "Rank symbols worth enriching within a token budget, and audit the decision. Calls no model.")
final class EnrichmentPlanCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Java repository to inspect") private Path repository;
    @CommandLine.Option(names = "--max-tokens", defaultValue = "20000", description = "Token budget") private int maxTokens;
    @CommandLine.Option(names = "--cost-per-1k", defaultValue = "0.003", description = "Cost per thousand tokens, for the audit line")
    private double costPerThousand;

    @CommandLine.Option(names = "--branches",
            description = "Rank index-tree branches instead of symbols: a summary there is read by every descent through it")
    private boolean branches;

    @CommandLine.Option(names = "--index", description = "Use a pinned tree file rather than deriving one")
    private java.nio.file.Path indexFile;

    @CommandLine.Mixin private AnalysisOptions options;

    @Override public Integer call() throws Exception {
        CodeGraph graph = options.analyze(repository);
        EnrichmentPlanner.Budget budget = new EnrichmentPlanner.Budget(maxTokens, costPerThousand);
        EnrichmentPlanner.Plan plan = branches
                ? io.softwareintelligence.queryengine.BranchEnrichment.plan(graph, TreeOptions.load(graph, indexFile), budget)
                : EnrichmentPlanner.plan(graph, budget);
        System.out.print(EnrichmentPlanner.audit(plan, budget));
        return 0;
    }
}

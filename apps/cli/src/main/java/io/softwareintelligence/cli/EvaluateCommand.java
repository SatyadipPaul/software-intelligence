package io.softwareintelligence.cli;

import io.softwareintelligence.evaluation.EvaluationHarness;
import io.softwareintelligence.evaluation.GroundedQuestion;
import io.softwareintelligence.evaluation.TextSearchBaseline;
import io.softwareintelligence.model.CodeGraph;
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
    @CommandLine.Mixin private AnalysisOptions options;

    @Override public Integer call() throws Exception {
        List<GroundedQuestion> set = EvaluationHarness.load(questions);
        CodeGraph graph = options.analyze(repository);
        EvaluationHarness.Report report = new EvaluationHarness(depth).run(graph, set);
        System.out.print(EvaluationHarness.render(report));
        if (baseline) System.out.print(baselineReport(set));
        return report.structuralAccuracy() + 1e-9 < failUnder ? 1 : 0;
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
    @CommandLine.Mixin private AnalysisOptions options;

    @Override public Integer call() throws Exception {
        CodeGraph graph = options.analyze(repository);
        EnrichmentPlanner.Budget budget = new EnrichmentPlanner.Budget(maxTokens, costPerThousand);
        System.out.print(EnrichmentPlanner.audit(EnrichmentPlanner.plan(graph, budget), budget));
        return 0;
    }
}

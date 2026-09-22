package io.softwareintelligence.cli;

import io.softwareintelligence.evaluation.EvaluationHarness;
import io.softwareintelligence.evaluation.GroundedQuestion;
import io.softwareintelligence.evaluation.RetrievalHarness;
import io.softwareintelligence.evaluation.TextSearchBaseline;
import io.softwareintelligence.indextree.IndexTree;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.embedding.EncoderFactory;
import io.softwareintelligence.embedding.TextEncoder;
import io.softwareintelligence.evaluation.OracleChooser;
import io.softwareintelligence.queryengine.DenseChooser;
import io.softwareintelligence.queryengine.DenseTreeIndex;
import io.softwareintelligence.queryengine.TreeNavigator;
import io.softwareintelligence.model.GraphQueries;
import io.softwareintelligence.queryengine.DenseIndex;
import io.softwareintelligence.queryengine.RetrievalMode;
import io.softwareintelligence.queryengine.EnrichmentPlanner;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(mixinStandardHelpOptions = true, hidden = true, name = "evaluate", description = "Score the engine against a grounded question set.")
final class EvaluateCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "REPOSITORY",
            description = "Java repository or graph file. Omit it to use the current directory.")
    private String first;

    @CommandLine.Parameters(index = "1", arity = "0..1", paramLabel = "QUESTIONS",
            description = "Tab-separated question set")
    private String second;

    /** Both resolved in call() from the positionals above, in either accepted order. */
    private Path repository;
    private Path questions;
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

    /** How a tree descent picks its branches, for measuring whether a better chooser helps. */
    enum ChooserKind { DETERMINISTIC, DENSE, DENSE_MAX, DENSE_CENTROID, ORACLE }

    @CommandLine.Option(names = "--chooser", defaultValue = "DETERMINISTIC",
            description = "How a descent picks branches: ${COMPLETION-CANDIDATES}. The DENSE variants "
                    + "differ only in how a branch folds the cards below it. ORACLE knows the "
                    + "answer and measures the ceiling the descent could reach, not a usable mode.")
    private ChooserKind chooser;

    @CommandLine.Option(names = "--embedding-model",
            description = "Model directory for the DENSE modes; defaults to the packaged model if one is on the classpath")
    private java.nio.file.Path embeddingModel;

    @CommandLine.Mixin private AnalysisOptions options;

    @Override public Integer call() throws Exception {
        Target target = options.target(first, second, "question set", this);
        repository = target.repository();
        questions = Path.of(target.subject());
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
            java.util.function.Function<GroundedQuestion, TreeNavigator.Chooser> chooserFor =
                    chooserFor(graph, tree, encoder);
            for (RetrievalMode mode : retrievalModes) {
                RetrievalHarness.Report report = harness.run(graph, tree, dense, set, mode, chooserFor);
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
     * When a dense mode <em>is</em> asked for, a configured model wins over a packaged one, so an
     * operator can always override what was shipped.
     */
    private TextEncoder openEncoder() {
        // A dense chooser needs the encoder as much as a dense mode does, and asking only about the
        // mode silently produced an empty run rather than a refusal.
        boolean wanted = retrievalModes.stream().anyMatch(RetrievalMode::needsDense)
                || chooser.name().startsWith("DENSE");
        if (!wanted) return null;
        return EncoderFactory.resolve(embeddingModel).orElseThrow(() -> new IllegalArgumentException(
                "a DENSE retrieval mode needs a model: add the embedding-model artifact to the "
                        + "classpath, or pass --embedding-model pointing at a directory with "
                        + "model.safetensors and vocab.txt"));
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

    /**
     * Builds the per-question branch chooser.
     *
     * <p>{@code ORACLE} is an instrument, not a mode: it resolves each question's declared answer to
     * graph ids and always descends towards it, so what it still fails to reach is the descent's own
     * ceiling rather than a chooser's mistake.
     */
    private java.util.function.Function<GroundedQuestion, TreeNavigator.Chooser> chooserFor(
            CodeGraph graph, IndexTree tree, TextEncoder encoder) {
        return switch (chooser) {
            case DETERMINISTIC -> null;
            case DENSE, DENSE_MAX, DENSE_CENTROID -> {
                if (encoder == null) {
                    throw new IllegalArgumentException("--chooser " + chooser + " needs a model: add the "
                            + "embedding-model artifact to the classpath, or pass --embedding-model");
                }
                DenseTreeIndex.Aggregation how = switch (chooser) {
                    case DENSE_MAX -> DenseTreeIndex.Aggregation.MAX;
                    case DENSE_CENTROID -> DenseTreeIndex.Aggregation.CENTROID;
                    default -> DenseTreeIndex.Aggregation.MEAN;
                };
                DenseTreeIndex treeVectors = DenseTreeIndex.over(tree, encoder, how);
                yield question -> new DenseChooser(encoder, treeVectors, question.question());
            }
            case ORACLE -> question -> OracleChooser.forTargets(tree, targets(graph, question));
        };
    }

    /** The graph ids a question's declared answer names, which is what the oracle descends towards. */
    private static java.util.Set<String> targets(CodeGraph graph, GroundedQuestion question) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        GraphQueries.findSymbol(graph, question.subject()).ifPresent(node -> ids.add(node.id()));
        for (String expected : question.expectedNames()) {
            GraphQueries.findSymbol(graph, expected).ifPresent(node -> ids.add(node.id()));
        }
        return ids;
    }
}

@CommandLine.Command(mixinStandardHelpOptions = true, name = "enrichment-plan",
        description = "Rank symbols worth enriching within a token budget, and audit the decision. Calls no model.")
final class EnrichmentPlanCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "REPOSITORY",
            description = "Java repository or graph file. Omit it to use the current directory.")
    private String repositoryArgument;

    /** Resolved once in call(): the positional, --repo, or the current directory. */
    private Path repository;
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
        repository = options.repository(repositoryArgument);
        CodeGraph graph = options.analyze(repository);
        EnrichmentPlanner.Budget budget = new EnrichmentPlanner.Budget(maxTokens, costPerThousand);
        EnrichmentPlanner.Plan plan = branches
                ? io.softwareintelligence.queryengine.BranchEnrichment.plan(graph, TreeOptions.load(graph, indexFile), budget)
                : EnrichmentPlanner.plan(graph, budget);
        System.out.print(EnrichmentPlanner.audit(plan, budget));
        return 0;
    }
}

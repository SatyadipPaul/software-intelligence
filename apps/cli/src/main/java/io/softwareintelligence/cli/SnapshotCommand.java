package io.softwareintelligence.cli;

import io.softwareintelligence.architecture.RiskScore;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.GraphQueries;
import io.softwareintelligence.model.GraphSnapshot;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(mixinStandardHelpOptions = true, name = "snapshot", description = "Write a durable graph snapshot for later comparison.")
final class SnapshotCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "REPOSITORY",
            description = "Java repository or graph file. Omit it to use the current directory.")
    private String repositoryArgument;

    /** Resolved once in call(): the positional, --repo, or the current directory. */
    private Path repository;
        @CommandLine.Option(names = {"-o", "--output"}, defaultValue = "repo-intel.snapshot.json", description = "Snapshot file")
    private Path output;
    @CommandLine.Mixin private AnalysisOptions options;

    @Override public Integer call() throws Exception {
        repository = options.repository(repositoryArgument);
        CodeGraph graph = options.analyze(repository);
        GraphSnapshot.write(graph, output.toAbsolutePath());
        System.out.printf("Wrote snapshot: %d nodes, %d edges to %s%n", graph.nodes().size(), graph.edges().size(), output.toAbsolutePath());
        return 0;
    }
}

@CommandLine.Command(mixinStandardHelpOptions = true, name = "diff",
        description = "Compare a snapshot against the working tree and rank the risk of what changed.")
final class DiffCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "REPOSITORY",
            description = "Java repository or graph file. Omit it to use the current directory.")
    private String first;

    @CommandLine.Parameters(index = "1", arity = "0..1", paramLabel = "SNAPSHOT",
            description = "Snapshot written by a previous run")
    private String second;

    /** Both resolved in call() from the positionals above, in either accepted order. */
    private Path repository;
    private Path snapshot;
    @CommandLine.Option(names = "--depth", defaultValue = "4", description = "Impact depth for the what-if simulation") private int depth;
    @CommandLine.Option(names = "--top", defaultValue = "5", description = "How many changed symbols to assess") private int top;
    @CommandLine.Mixin private AnalysisOptions options;

    @Override public Integer call() throws Exception {
        CodeGraph before = GraphSnapshot.read(snapshot);
        Target target = options.target(first, second, "snapshot", this);
        repository = target.repository();
        snapshot = Path.of(target.subject());
        CodeGraph after = options.analyze(repository);
        GraphSnapshot.Diff diff = GraphSnapshot.diff(before, after);
        System.out.print(GraphSnapshot.render(diff));
        if (diff.isEmpty()) return 0;

        // What-if: for each type whose surface changed, score the blast radius in the new graph.
        List<String> changed = GraphSnapshot.changedSymbols(diff);
        System.out.printf("%nWHAT-IF: %d changed types, assessing the %d highest-impact%n", changed.size(), Math.min(top, changed.size()));
        changed.stream()
                .map(id -> after.node(id).orElse(null)).filter(java.util.Objects::nonNull)
                .map(node -> RiskScore.assess(after, GraphQueries.impact(after, node, depth)))
                .sorted(java.util.Comparator.comparingDouble(RiskScore.Assessment::score).reversed())
                .limit(top)
                .forEach(assessment -> System.out.print(RiskScore.explain(assessment)));
        return 0;
    }
}

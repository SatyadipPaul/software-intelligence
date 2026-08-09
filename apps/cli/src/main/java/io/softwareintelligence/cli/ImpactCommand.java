package io.softwareintelligence.cli;

import io.softwareintelligence.architecture.RiskScore;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphQueries;
import io.softwareintelligence.model.ImpactReport;
import picocli.CommandLine;

import java.nio.file.Path;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "impact", description = "Show source-backed direct and transitive impact for a Java symbol.")
final class ImpactCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Java repository to inspect")
    private Path repository;

    @CommandLine.Parameters(index = "1", description = "Exact graph id, name, or graph-id suffix (for example: PaymentService or #authorize/0)")
    private String symbol;

    @CommandLine.Option(names = {"-d", "--depth"}, defaultValue = "4", description = "Maximum transitive traversal depth")
    private int depth;

    @CommandLine.Option(names = "--risk", description = "Also print an explainable change-risk score")
    private boolean risk;

    @CommandLine.Mixin private AnalysisOptions options;

    @Override public Integer call() throws Exception {
        if (depth < 1) throw new CommandLine.ParameterException(new CommandLine(this), "--depth must be at least 1");
        CodeGraph graph = options.analyze(repository);
        var subject = Symbols.resolve(graph, symbol, this);
        ImpactReport impact = GraphQueries.impact(graph, subject, depth);
        System.out.printf("IMPACT: %s (%s)%n", subject.name(), subject.kind());
        System.out.printf("Direct: %d | Transitive: %d | Depth: %d%n", impact.direct().size(), impact.transitive().size(), depth);
        print("DIRECT", impact.direct());
        print("TRANSITIVE", impact.transitive());
        if (risk) System.out.print(System.lineSeparator() + RiskScore.explain(RiskScore.assess(graph, impact)));
        return 0;
    }


    private static void print(String section, java.util.List<ImpactReport.ImpactPath> paths) {
        if (paths.isEmpty()) return;
        System.out.println(section + " EVIDENCE:");
        for (ImpactReport.ImpactPath path : paths) {
            GraphEdge finalEdge = path.evidence().get(path.evidence().size() - 1);
            System.out.printf("  - %s [%s] via %s at %s:%d (confidence %.2f)%n", path.target().name(), path.target().kind(), finalEdge.kind(),
                    finalEdge.provenance().file(), finalEdge.provenance().line(), finalEdge.provenance().confidence());
        }
    }
}

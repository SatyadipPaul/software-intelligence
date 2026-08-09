package io.softwareintelligence.cli;

import io.softwareintelligence.analyzer.java.JavaRepositoryAnalyzer;
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

    @CommandLine.Option(names = "--classpath", description = "Classpath entries separated by the platform path separator")
    private String classpath;

    @Override public Integer call() throws Exception {
        if (depth < 1) throw new CommandLine.ParameterException(new CommandLine(this), "--depth must be at least 1");
        CodeGraph graph = new JavaRepositoryAnalyzer().analyze(repository, classpathEntries());
        var subject = GraphQueries.findSymbol(graph, symbol).orElseThrow(() -> new CommandLine.ParameterException(new CommandLine(this), "No symbol matched: " + symbol));
        ImpactReport impact = GraphQueries.impact(graph, subject, depth);
        System.out.printf("IMPACT: %s (%s)%n", subject.name(), subject.kind());
        System.out.printf("Direct: %d | Transitive: %d | Depth: %d%n", impact.direct().size(), impact.transitive().size(), depth);
        print("DIRECT", impact.direct());
        print("TRANSITIVE", impact.transitive());
        return 0;
    }

    private List<Path> classpathEntries() {
        if (classpath == null || classpath.isBlank()) return List.of();
        return Arrays.stream(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .filter(value -> !value.isBlank()).map(Path::of).toList();
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

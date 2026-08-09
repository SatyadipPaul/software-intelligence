package io.softwareintelligence.cli;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.GraphJsonWriter;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "inspect", description = "Analyze a Java repository and write a canonical graph JSON file.")
final class InspectCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Java repository to inspect")
    private Path repository;

    @CommandLine.Option(names = {"-o", "--output"}, defaultValue = "repo-graph.json", description = "Output JSON file")
    private Path output;

    @CommandLine.Mixin private AnalysisOptions options;

    @Override public Integer call() throws IOException {
        CodeGraph graph = options.analyze(repository);
        Path destination = output.toAbsolutePath();
        if (destination.getParent() != null) Files.createDirectories(destination.getParent());
        Files.writeString(destination, GraphJsonWriter.write(graph));
        System.out.printf("Wrote %d nodes and %d edges to %s%n", graph.nodes().size(), graph.edges().size(), destination);
        return 0;
    }

}

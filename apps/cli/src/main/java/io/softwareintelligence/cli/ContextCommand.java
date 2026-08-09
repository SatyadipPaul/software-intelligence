package io.softwareintelligence.cli;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.ContextPacket;
import io.softwareintelligence.model.GraphJsonWriter;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.GraphQueries;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

@CommandLine.Command(name = "context", description = "Create a minimum-sufficient evidence packet for a symbol.")
final class ContextCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Java repository to inspect") private Path repository;
    @CommandLine.Parameters(index = "1", description = "Graph symbol id, name, or id suffix") private String symbol;
    @CommandLine.Option(names = {"-d", "--depth"}, description = "Maximum impact depth", defaultValue = "3") private int depth;
    @CommandLine.Option(names = {"-o", "--output"}, description = "Output JSON file", defaultValue = "context-packet.json") private Path output;
    @CommandLine.Mixin private AnalysisOptions options;

    @Override public Integer call() throws Exception {
        if (depth < 1) throw new CommandLine.ParameterException(new CommandLine(this), "--depth must be at least 1");
        CodeGraph graph = options.analyze(repository);
        GraphNode subject = Symbols.resolve(graph, symbol, this);
        ContextPacket packet = GraphQueries.context(graph, subject, depth);
        Path destination = output.toAbsolutePath();
        if (destination.getParent() != null) Files.createDirectories(destination.getParent());
        Files.writeString(destination, GraphJsonWriter.writeContext(packet));
        System.out.printf("Wrote context packet: callers=%d endpoints=%d dependencies=%d evidence=%d to %s%n",
                packet.callers().size(), packet.endpoints().size(), packet.dependencies().size(), packet.evidence().size(), destination);
        return 0;
    }

}

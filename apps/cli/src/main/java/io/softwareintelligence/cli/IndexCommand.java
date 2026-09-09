package io.softwareintelligence.cli;

import io.softwareintelligence.indextree.IndexKind;
import io.softwareintelligence.indextree.IndexNode;
import io.softwareintelligence.indextree.IndexTree;
import io.softwareintelligence.indextree.IndexTreeBuilder;
import io.softwareintelligence.indextree.IndexTreeJson;
import io.softwareintelligence.model.CodeGraph;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "index",
        description = "Derive the navigable index tree from a graph, and pin it to a file.")
final class IndexCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Java repository or graph file") private Path repository;

    @CommandLine.Option(names = {"-o", "--output"}, description = "Where to write the tree; omit to print a summary only")
    private Path output;

    @CommandLine.Option(names = "--max-fanout", defaultValue = "24",
            description = "Most children one card may present before they are grouped")
    private int maxFanout;

    @CommandLine.Option(names = "--max-members", defaultValue = "24",
            description = "Most methods listed under one type")
    private int maxMembers;

    @CommandLine.Option(names = "--no-members", description = "Stop the tree at types rather than descending to methods")
    private boolean noMembers;

    @CommandLine.Mixin private AnalysisOptions options;

    @Override public Integer call() throws Exception {
        CodeGraph graph = options.analyze(repository);
        IndexTree tree = IndexTreeBuilder.derive(graph,
                new IndexTreeBuilder.Options(maxFanout, maxMembers, !noMembers));

        Map<IndexKind, Integer> byKind = new TreeMap<>();
        for (IndexNode node : tree.nodes()) byKind.merge(node.kind(), 1, Integer::sum);

        System.out.printf("INDEX TREE: %d entries, depth %d, over %d graph nodes%n",
                tree.size(), tree.depth(), graph.nodes().size());
        System.out.printf("  graph fingerprint %s%n", tree.fingerprint());
        byKind.forEach((kind, count) -> System.out.printf("  %-12s %d%n", kind, count));
        if (byKind.getOrDefault(IndexKind.CAPABILITY, 0) == 0) {
            System.out.println("  note: this graph proves no capabilities, so the tree has its structural axis only");
        }

        if (output != null) {
            IndexTreeJson.write(tree, output);
            System.out.printf("%nwrote %s%n", output);
        }
        return 0;
    }
}

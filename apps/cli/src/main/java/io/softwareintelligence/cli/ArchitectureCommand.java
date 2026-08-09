package io.softwareintelligence.cli;

import io.softwareintelligence.architecture.Capabilities;
import io.softwareintelligence.architecture.Centrality;
import io.softwareintelligence.architecture.Communities;
import io.softwareintelligence.architecture.Modules;
import io.softwareintelligence.architecture.Workflows;
import io.softwareintelligence.model.CodeGraph;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "architecture", description = "Report modules, coupling, centrality, communities, and workflows.")
final class ArchitectureCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Java repository to inspect") private Path repository;
    @CommandLine.Mixin private AnalysisOptions options;

    @CommandLine.Option(names = "--communities", description = "Clustering strategy: CONNECTED_COMPONENTS or K_CORE", defaultValue = "CONNECTED_COMPONENTS")
    private Communities.Strategy strategy;

    @CommandLine.Option(names = "-k", description = "Minimum degree for K_CORE", defaultValue = "2") private int k;
    @CommandLine.Option(names = "--top", description = "How many ranked entries to print", defaultValue = "10") private int top;

    @Override public Integer call() throws Exception {
        CodeGraph graph = options.analyze(repository);
        List<Modules.Subsystem> subsystems = Modules.detect(graph);
        System.out.printf("MODULES (%d)%n", subsystems.size());
        for (Modules.Subsystem subsystem : subsystems) {
            System.out.printf("  %-45s types=%-4d depends on %s%n", subsystem.name(), subsystem.types().size(),
                    subsystem.dependsOn().isEmpty() ? "nothing" : subsystem.dependsOn());
        }

        List<Centrality.Score> ranked = Centrality.rank(graph);
        System.out.printf("%nMOST DEPENDED-UPON TYPES (top %d of %d)%n", Math.min(top, ranked.size()), ranked.size());
        ranked.stream().limit(top).forEach(score -> System.out.printf("  %-60s pageRank=%.5f in=%-4d out=%d%n",
                score.id(), score.pageRank(), score.inDegree(), score.outDegree()));

        List<Communities.Community> communities = Communities.detect(graph, strategy, k);
        System.out.printf("%nCOMMUNITIES via %s (%d)%n", strategy, communities.size());
        communities.stream().limit(top).forEach(community -> System.out.printf("  %-45s members=%-4d internalEdges=%d%n",
                community.id(), community.members().size(), community.cohesion()));

        List<Workflows.Workflow> workflows = Workflows.discover(graph, 8);
        System.out.printf("%nWORKFLOWS (%d)%n", workflows.size());
        workflows.stream().limit(top).forEach(workflow -> System.out.printf("  %-45s steps=%-4d touches=%s%n",
                workflow.entryPoint(), workflow.steps().size(), workflow.touches()));

        List<Capabilities.Capability> capabilities = Capabilities.detect(graph);
        System.out.printf("%nCAPABILITIES (%d)%n", capabilities.size());
        capabilities.stream().limit(top).forEach(capability -> System.out.printf("  %-30s entryPoints=%-4d entities=%s%n",
                capability.name(), capability.entryPoints().size(), capability.entities()));
        return 0;
    }
}

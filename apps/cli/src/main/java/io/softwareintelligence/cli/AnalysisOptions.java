package io.softwareintelligence.cli;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.pipeline.ClasspathDiscovery;
import io.softwareintelligence.pipeline.RepositoryModel;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** The analysis inputs every command shares, so a flag means the same thing everywhere. */
final class AnalysisOptions {
    @CommandLine.Option(names = "--classpath", description = "Classpath entries separated by the platform path separator")
    private String classpath;

    @CommandLine.Option(names = "--discover-classpath", description = "Use a classpath file or build output already present in the repository")
    private boolean discover;

    @CommandLine.Option(names = "--no-framework", description = "Skip Spring/JPA/Kafka interpretation")
    private boolean noFramework;

    @CommandLine.Option(names = "--no-architecture", description = "Skip module, workflow, and community summaries")
    private boolean noArchitecture;

    @CommandLine.Option(names = "--no-tests", description = "Exclude test sources")
    private boolean noTests;

    CodeGraph analyze(Path repository) throws IOException {
        List<Path> entries = classpathEntries();
        if (entries.isEmpty() && discover) {
            ClasspathDiscovery.Discovered discovered = ClasspathDiscovery.discover(repository);
            if (discovered.isEmpty()) System.err.println(discovered.advice());
            else System.err.printf("classpath: %d entries from %s%n", discovered.entries().size(), discovered.source());
            entries = discovered.entries();
        }
        RepositoryModel.Layers layers = new RepositoryModel.Layers(!noFramework, !noArchitecture, 8);
        return new RepositoryModel().build(repository, entries, !noTests, layers);
    }

    private List<Path> classpathEntries() {
        if (classpath == null || classpath.isBlank()) return List.of();
        return Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator)))
                .filter(value -> !value.isBlank()).map(Path::of).toList();
    }
}

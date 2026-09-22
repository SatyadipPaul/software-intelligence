package io.softwareintelligence.cli;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.GraphSnapshot;
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
    @CommandLine.Option(names = {"-C", "--repo"},
            description = "Repository or graph file to work on. Defaults to the current directory, "
                    + "and is ignored when one is given positionally.")
    private Path repo;

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

    @CommandLine.Option(names = "--test-vocabulary",
            description = "Read test method names as prose about the undocumented code they exercise. "
                    + "Off by default: it measured negative on every repository benchmarked here.")
    private boolean testVocabulary;

    @CommandLine.Option(names = "--commit-vocabulary",
            description = "Read commit subjects as prose about the undocumented files they changed. "
                    + "Off by default: it reads git history rather than the source at the analyzed "
                    + "commit, needs a full clone, and refuses a shallow one.")
    private boolean commitVocabulary;

    /**
     * Loads a graph from whatever the user pointed at: a `.json` graph or snapshot is read back,
     * and anything else is analyzed as a repository.
     *
     * <p>This lives here rather than in each command so that every command accepts both. Someone
     * who already has a graph should be able to query, visualize, enrich, and evaluate it without
     * the source tree present at all.
     */
    CodeGraph analyze(Path repository) throws IOException {
        if (isGraphFile(repository)) return GraphSnapshot.read(repository);
        List<Path> entries = classpathEntries();
        if (entries.isEmpty() && discover) {
            ClasspathDiscovery.Discovered discovered = ClasspathDiscovery.discover(repository);
            if (discovered.isEmpty()) {
                System.err.println(discovered.advice());
            } else {
                System.err.printf("classpath: %d entries from %s%n", discovered.entries().size(), discovered.source());
                if (discovered.partial()) {
                    // Compiled output without third-party jars barely improves resolution. Saying
                    // "26 entries" and stopping would overstate what the caller is about to get.
                    System.err.println("warning: these are compiled output directories only, with no third-party "
                            + "dependencies, so cross-library calls will stay unresolved.");
                    System.err.println(discovered.advice());
                }
            }
            entries = discovered.entries();
        }
        RepositoryModel.Layers layers = RepositoryModel.Layers.all()
                .withFramework(!noFramework)
                .withArchitecture(!noArchitecture)
                .withTestVocabulary(testVocabulary)
                .withCommitVocabulary(commitVocabulary);
        return new RepositoryModel().build(repository, entries, !noTests, layers);
    }

    /** The repository a command should use when it takes one positional, which it may omit. */
    Path repository(String positional) {
        return Target.repository(positional, repo);
    }

    /** The repository and subject for a command that takes both, in either of the accepted forms. */
    Target target(String first, String second, String subject, Object command) {
        return Target.of(first, second, repo, subject, command);
    }

    /** A path is an existing graph when it is a JSON file, and a repository otherwise. */
    static boolean isGraphFile(Path path) {
        return java.nio.file.Files.isRegularFile(path)
                && path.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json");
    }

    private List<Path> classpathEntries() {
        if (classpath == null || classpath.isBlank()) return List.of();
        return Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator)))
                .filter(value -> !value.isBlank()).map(Path::of).toList();
    }
}

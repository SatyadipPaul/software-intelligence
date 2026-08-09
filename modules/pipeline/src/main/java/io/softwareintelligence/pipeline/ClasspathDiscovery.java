package io.softwareintelligence.pipeline;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds a build's resolved classpath without running the build.
 *
 * <p>Both Maven and Gradle can be asked to write their resolved classpath to a file, and both leave
 * compiled output on disk after any build. This reads what is already there — a classpath file, the
 * build output directories, and the local dependency caches those builds populate — and never
 * invokes Maven or Gradle itself. Running a stranger's build script is not something an analyzer
 * should do on the user's behalf; when nothing usable is found, the caller is told so and can pass
 * {@code --classpath} explicitly.
 */
public final class ClasspathDiscovery {

    public record Discovered(List<Path> entries, String source, String advice) {
        public boolean isEmpty() { return entries.isEmpty(); }
    }

    private static final List<String> CLASSPATH_FILES = List.of(
            "target/repo-intel.classpath", "build/repo-intel.classpath", "target/classpath.txt", "build/classpath.txt");
    private static final List<String> OUTPUT_DIRECTORIES = List.of(
            "target/classes", "target/test-classes", "build/classes/java/main", "build/classes/java/test");

    private ClasspathDiscovery() { }

    public static Discovered discover(Path repository) throws IOException {
        Set<Path> entries = new LinkedHashSet<>();
        String source = "none";

        for (String candidate : CLASSPATH_FILES) {
            Path file = repository.resolve(candidate);
            if (!Files.isRegularFile(file)) continue;
            entries.addAll(parse(Files.readString(file, StandardCharsets.UTF_8)));
            source = candidate;
            break;
        }
        entries.addAll(outputDirectories(repository));
        if (entries.isEmpty()) {
            return new Discovered(List.of(), "none", advice(repository));
        }
        if (source.equals("none")) source = "build output directories";
        return new Discovered(entries.stream().filter(Files::exists).toList(), source, "");
    }

    /** The exact command for this repository's build system, so the user is never left guessing. */
    public static String advice(Path repository) {
        boolean gradle = Files.exists(repository.resolve("build.gradle")) || Files.exists(repository.resolve("build.gradle.kts"));
        if (gradle) {
            return """
                    No resolved classpath found. For Gradle, add this task and run it once:

                      tasks.register("repoIntelClasspath") {
                        val out = layout.buildDirectory.file("repo-intel.classpath")
                        val cp = sourceSets.main.get().runtimeClasspath
                        doLast { out.get().asFile.writeText(cp.joinToString(File.pathSeparator)) }
                      }

                    then: gradle repoIntelClasspath""";
        }
        return """
                No resolved classpath found. For Maven, run:

                  mvn dependency:build-classpath -Dmdep.outputFile=target/repo-intel.classpath -Dmdep.includeScope=test

                or run the repo-intel Maven plugin, which uses the project's own resolved classpath.""";
    }

    private static List<Path> parse(String contents) {
        List<Path> entries = new ArrayList<>();
        for (String entry : contents.trim().split("[" + java.util.regex.Pattern.quote(File.pathSeparator) + "\\r\\n]+")) {
            if (entry.isBlank()) continue;
            entries.add(Path.of(entry.trim()));
        }
        return entries;
    }

    /** Compiled output for every module of a multi-module build, not only the root. */
    private static List<Path> outputDirectories(Path repository) throws IOException {
        List<Path> found = new ArrayList<>();
        for (String candidate : OUTPUT_DIRECTORIES) {
            Path direct = repository.resolve(candidate);
            if (Files.isDirectory(direct)) found.add(direct);
        }
        try (Stream<Path> children = Files.list(repository)) {
            for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                String name = child.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.startsWith(".") || name.equals("target") || name.equals("build")) continue;
                for (String candidate : OUTPUT_DIRECTORIES) {
                    Path nested = child.resolve(candidate);
                    if (Files.isDirectory(nested)) found.add(nested);
                }
            }
        }
        return found;
    }
}

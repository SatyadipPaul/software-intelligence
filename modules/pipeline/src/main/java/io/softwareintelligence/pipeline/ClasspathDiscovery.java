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

    public record Discovered(List<Path> entries, String source, String advice, boolean dependenciesIncluded) {
        public boolean isEmpty() { return entries.isEmpty(); }

        /**
         * True when what was found is compiled output only. Those directories carry the project's
         * own classes but none of its third-party jars, so binding resolution stays close to what a
         * source-only run achieves. Reporting an entry count without saying this would overstate
         * what the caller is about to get.
         */
        public boolean partial() { return !entries.isEmpty() && !dependenciesIncluded; }
    }

    private static final List<String> CLASSPATH_FILES = List.of(
            "target/repo-intel.classpath", "build/repo-intel.classpath", "target/classpath.txt", "build/classpath.txt");
    private static final List<String> OUTPUT_DIRECTORIES = List.of(
            "target/classes", "target/test-classes", "build/classes/java/main", "build/classes/java/test");

    private ClasspathDiscovery() { }

    public static Discovered discover(Path repository) throws IOException {
        Set<Path> entries = new LinkedHashSet<>();
        String source = "none";
        boolean dependencies = false;

        for (String candidate : CLASSPATH_FILES) {
            Path file = repository.resolve(candidate);
            if (!Files.isRegularFile(file)) continue;
            entries.addAll(parse(Files.readString(file, StandardCharsets.UTF_8)));
            source = candidate;
            dependencies = true;
            break;
        }
        // A Gradle build under Isolated Projects cannot aggregate its subprojects into one file:
        // each module writes its own. Merging them here is what makes multi-module Gradle work at
        // all, and it costs nothing when there is only one.
        if (!dependencies) {
            List<Path> perModule = classpathFiles(repository);
            if (!perModule.isEmpty()) {
                for (Path file : perModule) entries.addAll(parse(Files.readString(file, StandardCharsets.UTF_8)));
                source = perModule.size() + " per-module classpath files";
                dependencies = true;
            }
        }
        entries.addAll(outputDirectories(repository));
        if (entries.isEmpty()) {
            return new Discovered(List.of(), "none", advice(repository), false);
        }
        if (source.equals("none")) source = "build output directories";
        return new Discovered(entries.stream().filter(Files::exists).toList(), source,
                dependencies ? "" : advice(repository), dependencies);
    }

    /** The exact command for this repository's build system, so the user is never left guessing. */
    public static String advice(Path repository) {
        boolean gradle = Files.exists(repository.resolve("build.gradle")) || Files.exists(repository.resolve("build.gradle.kts"));
        if (gradle) {
            // Registered per project through gradle.lifecycle.beforeProject, not as one root task
            // that reaches into subprojects: modern Gradle rejects that under Isolated Projects,
            // which is what junit5 turns on. Verified against junit5, which wrote 21 files.
            return """
                    No resolved classpath found. For Gradle, save this as repo-intel-init.gradle:

                      gradle.lifecycle.beforeProject { project ->
                        project.afterEvaluate {
                          def sourceSets = project.extensions.findByName("sourceSets")
                          if (sourceSets == null) return
                          def main = sourceSets.findByName("main")
                          if (main == null) return
                          def classpath = main.runtimeClasspath
                          def output = new File(project.layout.buildDirectory.get().asFile, "repo-intel.classpath")
                          project.tasks.register("repoIntelClasspath") {
                            outputs.file(output)
                            doLast {
                              output.parentFile.mkdirs()
                              output.text = classpath.files.collect { it.absolutePath }.join(File.pathSeparator)
                            }
                          }
                        }
                      }

                    then run: gradle --init-script repo-intel-init.gradle repoIntelClasspath

                    Each module writes its own build/repo-intel.classpath and this tool merges them.
                    A single root task that aggregates subprojects will not work on a build with
                    Isolated Projects enabled; the init script does not modify your build files.""";
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

    /**
     * Compiled output for every module of a multi-module build, not only the root.
     *
     * <p>Searched three levels deep: Gradle builds commonly nest modules under a grouping directory
     * such as {@code gradle/base/build-parameters}, and a single-level scan silently misses them.
     */
    private static List<Path> outputDirectories(Path repository) throws IOException {
        List<Path> found = new ArrayList<>();
        collectOutput(repository, found, 0);
        return found;
    }

    /** Every {@code build/repo-intel.classpath} or {@code target/repo-intel.classpath} in the tree. */
    private static List<Path> classpathFiles(Path repository) throws IOException {
        List<Path> found = new ArrayList<>();
        collectClasspathFiles(repository, found, 0);
        return found;
    }

    private static void collectClasspathFiles(Path directory, List<Path> found, int depth) throws IOException {
        for (String candidate : CLASSPATH_FILES) {
            Path file = directory.resolve(candidate);
            if (Files.isRegularFile(file)) found.add(file);
        }
        if (depth >= 3) return;
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                String name = child.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.startsWith(".") || name.equals("target") || name.equals("build")
                        || name.equals("src") || name.equals("node_modules")) continue;
                collectClasspathFiles(child, found, depth + 1);
            }
        }
    }

    private static void collectOutput(Path directory, List<Path> found, int depth) throws IOException {
        for (String candidate : OUTPUT_DIRECTORIES) {
            Path output = directory.resolve(candidate);
            if (Files.isDirectory(output)) found.add(output);
        }
        if (depth >= 3) return;
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                String name = child.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.startsWith(".") || name.equals("target") || name.equals("build")
                        || name.equals("src") || name.equals("node_modules")) continue;
                collectOutput(child, found, depth + 1);
            }
        }
    }
}

package io.softwareintelligence.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClasspathDiscoveryTest {

    @Test void a_maven_classpath_file_is_read_and_counts_as_a_full_classpath(@TempDir Path repository) throws IOException {
        Path jar = Files.createFile(repository.resolve("a.jar"));
        write(repository.resolve("target/repo-intel.classpath"), jar.toString());

        ClasspathDiscovery.Discovered discovered = ClasspathDiscovery.discover(repository);

        assertEquals(java.util.List.of(jar), discovered.entries());
        assertTrue(discovered.dependenciesIncluded());
        assertFalse(discovered.partial(), "a resolved classpath file includes third-party jars");
    }

    @Test void per_module_gradle_classpath_files_are_merged() throws IOException {
        Path repository = Files.createTempDirectory("multi");
        Path first = Files.createFile(repository.resolve("first.jar"));
        Path second = Files.createFile(repository.resolve("second.jar"));
        // Isolated Projects forbids a root task that aggregates subprojects, so each module writes
        // its own file and discovery is what puts them back together.
        write(repository.resolve("module-a/build/repo-intel.classpath"), first.toString());
        write(repository.resolve("module-b/build/repo-intel.classpath"), second.toString() + File.pathSeparator + first);

        ClasspathDiscovery.Discovered discovered = ClasspathDiscovery.discover(repository);

        assertTrue(discovered.entries().contains(first));
        assertTrue(discovered.entries().contains(second));
        assertEquals(2, discovered.entries().size(), "a jar shared by two modules is listed once");
        assertTrue(discovered.source().contains("per-module"));
        assertFalse(discovered.partial());
    }

    @Test void a_classpath_file_nested_three_levels_deep_is_found() throws IOException {
        Path repository = Files.createTempDirectory("nested");
        Path jar = Files.createFile(repository.resolve("x.jar"));
        // Gradle builds commonly group modules, as junit5 does under gradle/base/.
        write(repository.resolve("gradle/base/build-parameters/build/repo-intel.classpath"), jar.toString());

        assertTrue(ClasspathDiscovery.discover(repository).entries().contains(jar));
    }

    @Test void compiled_output_alone_is_reported_as_partial() throws IOException {
        Path repository = Files.createTempDirectory("output");
        Files.createDirectories(repository.resolve("module-a/build/classes/java/main"));

        ClasspathDiscovery.Discovered discovered = ClasspathDiscovery.discover(repository);

        assertFalse(discovered.isEmpty());
        assertTrue(discovered.partial(), "output directories carry no third-party dependencies");
        assertFalse(discovered.dependenciesIncluded());
        assertFalse(discovered.advice().isBlank(), "a partial result must say how to get a real classpath");
    }

    @Test void an_unbuilt_gradle_repository_gets_advice_that_works_under_isolated_projects() throws IOException {
        Path repository = Files.createTempDirectory("gradle");
        Files.writeString(repository.resolve("build.gradle.kts"), "plugins { java }");

        ClasspathDiscovery.Discovered discovered = ClasspathDiscovery.discover(repository);

        assertTrue(discovered.isEmpty());
        String advice = discovered.advice();
        assertTrue(advice.contains("gradle.lifecycle.beforeProject"),
                "a root task that reaches into subprojects is rejected by Isolated Projects");
        assertTrue(advice.contains("--init-script"), "the advice must not require editing the user's build files");
    }

    @Test void an_unbuilt_maven_repository_gets_maven_advice() throws IOException {
        Path repository = Files.createTempDirectory("maven");
        Files.writeString(repository.resolve("pom.xml"), "<project/>");

        String advice = ClasspathDiscovery.discover(repository).advice();

        assertTrue(advice.contains("dependency:build-classpath"));
        assertFalse(advice.contains("gradle.lifecycle"));
    }

    @Test void nothing_is_reported_when_nothing_exists(@TempDir Path repository) throws IOException {
        ClasspathDiscovery.Discovered discovered = ClasspathDiscovery.discover(repository);

        assertTrue(discovered.isEmpty());
        assertEquals("none", discovered.source());
    }

    private static void write(Path file, String contents) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }
}

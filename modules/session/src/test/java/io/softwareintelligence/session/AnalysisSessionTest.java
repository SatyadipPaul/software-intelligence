package io.softwareintelligence.session;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.pipeline.RepositoryModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a session must keep, and what it must throw away. */
final class AnalysisSessionTest {

    private static void write(Path repository, String name, String content) throws IOException {
        Path file = repository.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static Path sample(Path repository) throws IOException {
        write(repository, "src/main/java/demo/Service.java", """
                package demo;
                /** Authorizes a payment. */
                public class Service {
                    public void authorize() { }
                }
                """);
        return repository;
    }

    @Test void derived_structures_are_built_once_and_reused(@TempDir Path directory) throws IOException {
        AnalysisSession session = AnalysisSession.open(sample(directory));

        // The point of the session: deriving these costs seconds on a real repository.
        assertSame(session.tree(), session.tree());
        assertSame(session.bm25(), session.bm25());
        assertSame(session.graph(), session.graph());
    }

    @Test void an_unchanged_repository_is_not_stale_and_refresh_does_nothing(@TempDir Path directory) throws IOException {
        AnalysisSession session = AnalysisSession.open(sample(directory));
        CodeGraph first = session.graph();

        assertFalse(session.stale());
        assertFalse(session.refresh(), "refresh must report that it did not rebuild");
        assertSame(first, session.graph(), "an unchanged repository must not be re-analyzed");
    }

    @Test void an_edit_makes_it_stale_and_refresh_rebuilds(@TempDir Path directory) throws IOException {
        AnalysisSession session = AnalysisSession.open(sample(directory));
        CodeGraph first = session.graph();

        write(directory, "src/main/java/demo/Service.java", """
                package demo;
                public class Service {
                    public void authorize() { }
                    public void refund() { }
                }
                """);

        assertTrue(session.stale());
        assertTrue(session.refresh(), "refresh must report that it rebuilt");
        assertNotSame(first, session.graph());
        assertFalse(session.stale(), "after rebuilding it must be current again");
    }

    @Test void a_rebuild_drops_everything_derived_from_the_old_graph(@TempDir Path directory) throws IOException {
        // The failure this prevents: answering from a tree and an index that describe the previous
        // source, which produces ids that no longer resolve rather than an error.
        AnalysisSession session = AnalysisSession.open(sample(directory));
        var staleTree = session.tree();
        var staleIndex = session.bm25();

        write(directory, "src/main/java/demo/Other.java", "package demo; public class Other { }");
        assertTrue(session.refresh());

        assertNotSame(staleTree, session.tree());
        assertNotSame(staleIndex, session.bm25());
    }

    @Test void an_unreadable_repository_reports_stale_rather_than_current(@TempDir Path directory) throws IOException {
        AnalysisSession session = AnalysisSession.open(sample(directory));
        deleteTree(directory);

        // Not knowing is not the same as knowing nothing changed, and the costs are not symmetric.
        assertTrue(session.stale());
    }

    @Test void the_request_records_how_the_graph_was_asked_for(@TempDir Path directory) throws IOException {
        AnalysisSession.Request request = new AnalysisSession.Request(sample(directory), List.of(), false,
                RepositoryModel.Layers.all().withArchitecture(false));
        AnalysisSession session = AnalysisSession.open(request);

        assertFalse(session.request().includeTests());
        assertFalse(session.request().layers().architecture());
    }

    @Test void a_request_defends_its_classpath_from_later_mutation(@TempDir Path directory) throws IOException {
        // A caller holding the list it passed must not be able to change what the session thinks
        // it built, or staleness is computed against a request that never happened.
        List<Path> mutable = new java.util.ArrayList<>(List.of(Path.of("a.jar")));
        AnalysisSession.Request request = new AnalysisSession.Request(sample(directory), mutable, true,
                RepositoryModel.Layers.all());
        mutable.add(Path.of("b.jar"));

        org.junit.jupiter.api.Assertions.assertEquals(1, request.classpath().size());
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}

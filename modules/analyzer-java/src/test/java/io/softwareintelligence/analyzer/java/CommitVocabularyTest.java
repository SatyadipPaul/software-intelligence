package io.softwareintelligence.analyzer.java;

import io.softwareintelligence.model.Attributes;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.Provenance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what a commit has to look like to describe a file, and what a subject becomes.
 *
 * <p>The integration tests build a real repository rather than stubbing git, because the thing most
 * likely to be wrong here is the shape of the log output, and a stub would agree with whatever this
 * code already assumes about it.
 */
class CommitVocabularyTest {

    private static final Provenance AT = new Provenance("JDT_AST", 1.0, "src/main/java/demo/Owner.java", 5, 1);

    @Test void a_subject_drops_the_verb_every_subject_starts_with() {
        assertEquals("pagination for owners and vets lists",
                CommitVocabulary.phrase("Add pagination for owners and vets lists"));
        // Only the verbs that start nearly every subject go. "normalize" survives because it says
        // something: a subject that begins with it is not interchangeable with one that begins
        // "Add", and a term index that sees it on three cards learns from it.
        assertEquals("normalize whitespace in owner search",
                CommitVocabulary.phrase("fix: normalize whitespace in owner search"));
    }

    @Test void a_ticket_number_is_not_vocabulary() {
        // The rarest token in the repository and the least meaningful to anyone asking a question.
        assertEquals("relation already exists in postgresql",
                CommitVocabulary.phrase("issue #2584: fix relation already exists in PostgreSQL"));
        assertEquals("display the pet type when using the jdbc profile",
                CommitVocabulary.phrase("GH-42 Display the pet type when using the JDBC profile"));
    }

    @Test void a_subject_that_is_only_a_verb_becomes_nothing() {
        assertEquals("", CommitVocabulary.phrase("Polish"));
        assertEquals("", CommitVocabulary.phrase("fix"));
        assertEquals("", CommitVocabulary.phrase("Minor cleanup"));
    }

    @Test void a_tool_tag_is_stripped_but_the_rest_of_a_release_subject_is_left_alone() {
        // Deliberately not a bot-pattern list. What removes a release commit is breadth - it
        // touches every POM - and a list of tool names is a list that goes stale in a repository
        // nobody who wrote it has seen. Only the bracketed tag itself is noise by construction.
        assertEquals("prepare release",
                CommitVocabulary.phrase("[maven-release-plugin] prepare release #123"));
    }

    // ------------------------------------------------------------------ against a real repository

    private static void run(Path repository, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(repository.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), String.join(" ", command) + " failed: " + output);
    }

    private static void commit(Path repository, String subject, String... files)
            throws IOException, InterruptedException {
        for (String file : files) {
            Path path = repository.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "// " + subject + System.nanoTime() + "\n");
        }
        run(repository, "git", "add", "-A");
        run(repository, "git", "commit", "-q", "-m", subject);
    }

    /** Two focused commits on one file, and one sweep that touches everything. */
    private static Path repository(@TempDir Path directory) throws IOException, InterruptedException {
        run(directory, "git", "init", "-q", "-b", "main");
        run(directory, "git", "config", "user.email", "test@example.invalid");
        run(directory, "git", "config", "user.name", "Test");
        commit(directory, "Add the owner aggregate", "src/main/java/demo/Owner.java");
        commit(directory, "Enforce unique pet names per owner", "src/main/java/demo/Owner.java");
        String[] everything = new String[CommitVocabulary.SWEEP + 1];
        for (int i = 0; i < everything.length; i++) everything[i] = "src/main/java/demo/Filler" + i + ".java";
        commit(directory, "Updated copyright to year 2025", everything);
        return directory;
    }

    private static CodeGraph graph() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(new GraphNode("repo:demo", EntityKind.REPOSITORY, "demo", Map.of(),
                new Provenance("JDT_AST", 1.0, "", 0, 0)), true);
        graph.upsertNode(new GraphNode("type:demo.Owner", EntityKind.TYPE, "demo.Owner", Map.of(), AT), true);
        return graph;
    }

    @Test void a_focused_commit_describes_the_file_it_changed(@TempDir Path directory) throws Exception {
        CodeGraph graph = graph();

        CommitVocabulary.attach(graph, repository(directory));

        String history = graph.node("type:demo.Owner").orElseThrow().attributes().get(Attributes.HISTORY);
        assertTrue(history.contains("unique pet names per owner"), String.valueOf(history));
        assertTrue(history.contains("owner aggregate"), String.valueOf(history));
    }

    @Test void a_sweep_describes_nothing(@TempDir Path directory) throws Exception {
        CodeGraph graph = graph();
        graph.upsertNode(new GraphNode("type:demo.Filler0", EntityKind.TYPE, "demo.Filler0", Map.of(),
                new Provenance("JDT_AST", 1.0, "src/main/java/demo/Filler0.java", 1, 1)), true);

        CommitVocabulary.attach(graph, repository(directory));

        // A copyright year touches every file and separates none of them.
        assertFalse(graph.node("type:demo.Filler0").orElseThrow().attributes().containsKey(Attributes.HISTORY));
        assertFalse(graph.node("type:demo.Owner").orElseThrow()
                .attributes().get(Attributes.HISTORY).contains("copyright"));
    }

    @Test void a_documented_type_keeps_its_own_sentence(@TempDir Path directory) throws Exception {
        CodeGraph graph = graph();
        graph.upsertNode(new GraphNode("type:demo.Owner", EntityKind.TYPE, "demo.Owner",
                Map.of(Attributes.DOC, "A client of the clinic."), AT), true);

        CommitVocabulary.attach(graph, repository(directory));

        assertFalse(graph.node("type:demo.Owner").orElseThrow().attributes().containsKey(Attributes.HISTORY));
    }

    @Test void the_graph_records_which_history_it_read(@TempDir Path directory) throws Exception {
        CodeGraph graph = graph();

        CommitVocabulary.attach(graph, repository(directory));

        Map<String, String> repository = graph.node("repo:demo").orElseThrow().attributes();
        // Without this a graph carrying history cannot say which history, and the one part of it
        // that is not a function of the source becomes unauditable.
        assertEquals(40, repository.get("history.commit").length());
        assertEquals("2", repository.get("history.focusedCommits"));
        assertEquals(Integer.toString(CommitVocabulary.SWEEP), repository.get("history.sweepThreshold"));
    }

    @Test void a_directory_that_is_not_a_repository_is_refused(@TempDir Path directory) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> CommitVocabulary.attach(graph(), directory));
        assertTrue(refused.getMessage().contains("not a git repository"), refused.getMessage());
    }

    @Test void a_shallow_clone_is_refused(@TempDir Path directory) throws Exception {
        Path origin = directory.resolve("origin");
        Files.createDirectories(origin);
        repository(origin);
        Path shallow = directory.resolve("shallow");
        run(directory, "git", "clone", "-q", "--depth", "1", "file://" + origin.toAbsolutePath(),
                shallow.toAbsolutePath().toString());

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> CommitVocabulary.attach(graph(), shallow));

        // Two shallow clones of one commit can hold different history. Tolerating that would make
        // this the only part of the graph that is not a function of the commit.
        assertTrue(refused.getMessage().contains("shallow"), refused.getMessage());
    }

    @Test void reading_the_same_commit_twice_gives_the_same_answer(@TempDir Path directory) throws Exception {
        Path repository = repository(directory);
        CodeGraph first = graph();
        CodeGraph second = graph();

        CommitVocabulary.attach(first, repository);
        CommitVocabulary.attach(second, repository);

        assertEquals(first.node("type:demo.Owner").orElseThrow().attributes(),
                second.node("type:demo.Owner").orElseThrow().attributes());
    }

    @Test void merges_are_not_read(@TempDir Path directory) throws Exception {
        Path repository = repository(directory);
        run(repository, "git", "checkout", "-q", "-b", "side", "HEAD~2");
        commit(repository, "Give a pet a birth date", "src/main/java/demo/Pet.java");
        run(repository, "git", "checkout", "-q", "main");
        run(repository, "git", "merge", "-q", "--no-ff", "-m", "Merge branch side into main", "side");
        CodeGraph graph = graph();
        graph.upsertNode(new GraphNode("type:demo.Pet", EntityKind.TYPE, "demo.Pet", Map.of(),
                new Provenance("JDT_AST", 1.0, "src/main/java/demo/Pet.java", 1, 1)), true);

        CommitVocabulary.attach(graph, repository);

        // A merge subject describes an integration, and its file list is the union of everything
        // merged - the widest sweep in the log.
        String pet = graph.node("type:demo.Pet").orElseThrow().attributes().get(Attributes.HISTORY);
        assertEquals("give pet birth date", pet);
        assertFalse(List.of(graph.node("type:demo.Owner").orElseThrow()
                .attributes().getOrDefault(Attributes.HISTORY, "")).toString().contains("merge"));
    }
}

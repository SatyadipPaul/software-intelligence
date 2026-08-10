package io.softwareintelligence.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a path is a graph or a repository decides which of two entirely different code paths
 * runs. Getting it wrong once already made ask, impact, and evaluate silently analyze a JSON file
 * as if it were a source tree and return an empty graph.
 */
class AnalysisOptionsTest {

    @Test void a_json_file_is_treated_as_an_existing_graph(@TempDir Path directory) throws IOException {
        Path graph = Files.writeString(directory.resolve("repo-graph.json"), "{}");

        assertTrue(AnalysisOptions.isGraphFile(graph));
    }

    @Test void the_extension_check_is_case_insensitive(@TempDir Path directory) throws IOException {
        assertTrue(AnalysisOptions.isGraphFile(Files.writeString(directory.resolve("Graph.JSON"), "{}")));
    }

    @Test void a_directory_is_a_repository_even_when_named_like_a_graph(@TempDir Path directory) throws IOException {
        Path looksLikeAGraph = Files.createDirectory(directory.resolve("weird.json"));

        assertFalse(AnalysisOptions.isGraphFile(looksLikeAGraph), "a directory is always a repository");
    }

    @Test void a_source_tree_is_not_a_graph_file(@TempDir Path directory) {
        assertFalse(AnalysisOptions.isGraphFile(directory));
    }

    @Test void a_path_that_does_not_exist_is_not_a_graph_file(@TempDir Path directory) {
        assertFalse(AnalysisOptions.isGraphFile(directory.resolve("absent.json")));
    }

    @Test void a_non_json_file_is_not_a_graph_file(@TempDir Path directory) throws IOException {
        assertFalse(AnalysisOptions.isGraphFile(Files.writeString(directory.resolve("notes.txt"), "x")));
    }
}

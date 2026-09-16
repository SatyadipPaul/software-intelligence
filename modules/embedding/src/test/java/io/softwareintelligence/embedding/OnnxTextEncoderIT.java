package io.softwareintelligence.embedding;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real encoder when one is configured, and skips otherwise.
 *
 * <p>Point it at a directory holding {@code model.onnx} and {@code vocab.txt}:
 * {@code -Drepo.intel.embedding.model=/path/to/model}. It is skipped rather than failed when absent
 * because the weights are not in this repository and CI must not depend on fetching 90 MB.
 */
class OnnxTextEncoderIT {

    private static Path configuredModel() {
        String configured = System.getProperty("repo.intel.embedding.model",
                System.getenv("REPO_INTEL_EMBEDDING_MODEL"));
        return configured == null || configured.isBlank() ? null : Path.of(configured);
    }

    @Test void encodes_normalised_vectors_and_ranks_meaning_above_shared_words() {
        Path model = configuredModel();
        assumeTrue(model != null && Files.isDirectory(model), "no embedding model configured");

        try (TextEncoder encoder = EncoderFactory.open(model)) {
            assertEquals(384, encoder.dimensions(), "all-MiniLM-L6-v2 produces 384 components");

            float[][] vectors = encoder.encode(List.of(
                    "Which annotation switches a test off without deleting it?",
                    "Disabled. Signals that the annotated test class or test method is currently disabled",
                    "Tag. Marks a test so that it can be selected or filtered by label",
                    "ObjectMapper. Reads and writes JSON"));

            for (float[] vector : vectors) {
                assertEquals(1.0, Math.sqrt(TextEncoder.similarity(vector, vector)), 1e-4, "vectors are normalised");
            }

            double toDisabled = TextEncoder.similarity(vectors[0], vectors[1]);
            double toTag = TextEncoder.similarity(vectors[0], vectors[2]);
            double toUnrelated = TextEncoder.similarity(vectors[0], vectors[3]);

            // The whole reason this module exists: the question and the answer share no content
            // word, and the encoder still has to put them closer together than a card that does
            // share words ("test", "marks") but means something else.
            assertTrue(toDisabled > toTag, "expected the disabled card to win, got " + toDisabled + " vs " + toTag);
            assertTrue(toDisabled > toUnrelated, "expected an unrelated card to lose, got " + toUnrelated);
        }
    }
}

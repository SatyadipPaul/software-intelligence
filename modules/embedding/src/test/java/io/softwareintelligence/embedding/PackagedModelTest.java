package io.softwareintelligence.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packaged-model path, exercised against a real (tiny) model on the test classpath.
 *
 * <p>This is the behaviour the weights artifact exists to provide: a consumer adds a dependency and
 * an encoder appears, with no flag and no path. Worth a test with actual bytes rather than a mock,
 * because the thing that breaks is the resource path, and a mock would agree with whatever the code
 * happened to look for.
 */
class PackagedModelTest {

    @Test void a_model_on_the_classpath_is_found_without_configuration() {
        Optional<TextEncoder> packaged = EncoderFactory.packaged();
        assertTrue(packaged.isPresent(), "the test resources carry a model under " + ModelSource.CLASSPATH_ROOT);
        try (TextEncoder encoder = packaged.get()) {
            assertEquals(4, encoder.dimensions());
            float[] vector = encoder.encodeOne("alpha beta");
            assertEquals(1.0, Math.sqrt(TextEncoder.similarity(vector, vector)), 1e-5);
        }
    }

    @Test void no_packaged_model_is_empty_rather_than_a_failure() {
        // A consumer who never wanted dense retrieval must not be made to handle an exception.
        ClassLoader bare = new URLClassLoader(new java.net.URL[0], null);
        assertTrue(EncoderFactory.packaged(bare).isEmpty());
    }

    @Test void a_configured_model_wins_over_the_packaged_one(@TempDir Path directory) throws Exception {
        // So an operator can override what was shipped — a newer model, a domain-tuned one, or the
        // transformer reference — without rebuilding the artifact.
        QuantizerTestSupport.writeTinyModel(directory, 6, 16);
        try (TextEncoder configured = EncoderFactory.resolve(directory).orElseThrow()) {
            assertEquals(16, configured.dimensions(), "the configured model's width, not the packaged one's");
        }
    }

    @Test void resolve_falls_back_to_the_packaged_model_when_nothing_is_configured() {
        try (TextEncoder resolved = EncoderFactory.resolve(null).orElseThrow()) {
            assertEquals(4, resolved.dimensions(), "the packaged model's width");
        }
    }

    // Nothing here asserts that the packaged model ranks *well*. The test model's vectors are
    // synthetic, so any such assertion would be testing the weights rather than the loading, and
    // would pass or fail on how the fake numbers happened to fall. Retrieval quality is the
    // benchmark's job; this file's job is that the files are found and read correctly.
}

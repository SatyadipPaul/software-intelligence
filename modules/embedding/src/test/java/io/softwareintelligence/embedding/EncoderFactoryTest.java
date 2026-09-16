package io.softwareintelligence.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncoderFactoryTest {

    @Test void an_unconfigured_encoder_is_a_configuration_state_not_a_failure() {
        EncoderUnavailableException failure =
                assertThrows(EncoderUnavailableException.class, () -> EncoderFactory.open(null));
        assertTrue(failure.getMessage().contains("no embedding model directory"));
    }

    @Test void a_directory_without_the_model_files_says_which_ones_it_wanted(@TempDir Path empty) {
        // Only meaningful when the optional runtime is present; without it the factory correctly
        // reports the runtime instead, which the message below would not match.
        if (!EncoderFactory.available()) return;
        EncoderUnavailableException failure =
                assertThrows(EncoderUnavailableException.class, () -> EncoderFactory.open(empty));
        assertTrue(failure.getMessage().contains("model.onnx"), failure.getMessage());
    }
}

package io.softwareintelligence.embedding;

import java.nio.file.Path;

/**
 * Builds an encoder if one is both asked for and available, and explains itself when it is not.
 *
 * <p>The ONNX runtime is an optional dependency, so this loads the backend reflectively: a consumer
 * who never configures an encoder neither downloads it nor fails to start without it. The check is
 * {@link #available()} rather than a {@code try/catch} at the call site, so a caller can decide to
 * take the lexical path without first provoking an exception.
 */
public final class EncoderFactory {

    private EncoderFactory() { }

    /** Whether the optional ONNX runtime is on the classpath at all. */
    public static boolean available() {
        try {
            Class.forName("ai.onnxruntime.OrtEnvironment");
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    /**
     * Opens the encoder in {@code modelDirectory}, which must hold {@code model.onnx} and
     * {@code vocab.txt}.
     *
     * @throws EncoderUnavailableException when the runtime is absent or the model cannot be read
     */
    public static TextEncoder open(Path modelDirectory) {
        if (modelDirectory == null) {
            throw new EncoderUnavailableException("no embedding model directory was configured");
        }
        if (!available()) {
            throw new EncoderUnavailableException(
                    "the ONNX runtime is not on the classpath; add com.microsoft.onnxruntime:onnxruntime "
                            + "to use a dense encoder, or run without one and retrieval stays lexical");
        }
        return new OnnxTextEncoder(modelDirectory);
    }
}

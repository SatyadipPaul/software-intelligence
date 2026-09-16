package io.softwareintelligence.embedding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

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
     * Opens whichever encoder {@code modelDirectory} holds.
     *
     * <p>A {@code model.safetensors} is a static model and is preferred when both are present: it
     * needs no native runtime, so it works everywhere the library does. A {@code model.onnx} needs
     * the optional runtime and is used when that is the only model there.
     *
     * @throws EncoderUnavailableException when nothing usable is there, or the ONNX model is there
     *         and its runtime is not
     */
    public static TextEncoder open(Path modelDirectory) {
        if (modelDirectory == null) {
            throw new EncoderUnavailableException("no embedding model directory was configured");
        }
        if (Files.isRegularFile(modelDirectory.resolve("model.safetensors"))) {
            return new StaticTextEncoder(ModelSource.ofDirectory(modelDirectory));
        }
        if (!Files.isRegularFile(modelDirectory.resolve("model.onnx"))) {
            throw new EncoderUnavailableException("expected model.safetensors or model.onnx in "
                    + modelDirectory.toAbsolutePath());
        }
        if (!available()) {
            throw new EncoderUnavailableException(
                    "this model needs the ONNX runtime, which is not on the classpath; add "
                            + "com.microsoft.onnxruntime:onnxruntime, use a static model that needs no "
                            + "runtime, or run without an encoder and retrieval stays lexical");
        }
        return new OnnxTextEncoder(modelDirectory);
    }

    /**
     * The model packaged on the classpath, when a weights artifact is on it.
     *
     * <p>This is what an optional artifact buys: add the dependency and dense retrieval works with
     * no flag and no path. Empty rather than throwing when nothing is packaged, because "no model
     * was shipped" is the ordinary case for a consumer who never asked for one.
     */
    public static Optional<TextEncoder> packaged() {
        return packaged(Thread.currentThread().getContextClassLoader() == null
                ? EncoderFactory.class.getClassLoader()
                : Thread.currentThread().getContextClassLoader());
    }

    static Optional<TextEncoder> packaged(ClassLoader loader) {
        ModelSource source = ModelSource.ofClasspath(loader);
        try (java.io.InputStream weights = source.open("model.safetensors")) {
            if (weights == null) return Optional.empty();
        } catch (java.io.IOException unreadable) {
            return Optional.empty();
        }
        return Optional.of(new StaticTextEncoder(source));
    }

    /**
     * The model to use: the one configured, else the one packaged, else none.
     *
     * <p>Configuration wins over packaging so an operator can always override what was shipped —
     * with a newer model, a domain-tuned one, or the transformer reference.
     */
    public static Optional<TextEncoder> resolve(Path configured) {
        return configured != null ? Optional.of(open(configured)) : packaged();
    }
}
package io.softwareintelligence.embedding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A static embedding model: one vector per token, looked up and averaged. No network, no runtime.
 *
 * <p>Distilled models of this shape (Model2Vec's {@code potion-*}) trade contextual meaning for an
 * enormous simplification — the forward pass is gone, and with it the native dependency. Inference
 * is a table lookup and a mean, which is why this class is short and why it is the only encoder here
 * that a library can plausibly bundle: no ONNX, no per-platform binaries, no JNI.
 *
 * <p>What it gives up is that a token means the same thing everywhere. The bet, which the benchmark
 * tests rather than assumes, is that the failure being fixed is register mismatch at the level of
 * words — "switches a test off" against {@code Disabled} — and that word-level meaning is exactly
 * what a static table holds.
 */
final class StaticTextEncoder implements TextEncoder {

    /**
     * Token cap per text. Generous because there is no quadratic attention to bound here; it exists
     * only so a pathological input cannot pull the whole table through a mean.
     */
    private static final int MAX_TOKENS = 4096;

    private final WordPieceTokenizer tokenizer;
    private final SafeTensors.Tensor embeddings;

    StaticTextEncoder(Path modelDirectory) {
        Path weights = modelDirectory.resolve("model.safetensors");
        Path vocabulary = modelDirectory.resolve("vocab.txt");
        if (!Files.isRegularFile(weights) || !Files.isRegularFile(vocabulary)) {
            throw new EncoderUnavailableException(
                    "expected model.safetensors and vocab.txt in " + modelDirectory.toAbsolutePath());
        }
        try {
            this.tokenizer = WordPieceTokenizer.fromVocabulary(vocabulary);
            this.embeddings = SafeTensors.readMatrix(weights, "embeddings");
        } catch (IOException failure) {
            throw new EncoderUnavailableException("could not load the static encoder from " + modelDirectory, failure);
        }
        if (embeddings.rows() < tokenizer.vocabularySize()) {
            throw new EncoderUnavailableException("the vocabulary has " + tokenizer.vocabularySize()
                    + " tokens but the table only has " + embeddings.rows() + " rows; they are not a pair");
        }
    }

    @Override public int dimensions() { return embeddings.columns(); }

    @Override
    public float[][] encode(List<String> texts) {
        float[][] vectors = new float[texts.size()][];
        for (int i = 0; i < texts.size(); i++) vectors[i] = encodeSingle(texts.get(i));
        return vectors;
    }

    private float[] encodeSingle(String text) {
        int columns = embeddings.columns();
        float[] pooled = new float[columns];
        int[] tokens = tokenizer.encodeContent(text, MAX_TOKENS);
        if (tokens.length == 0) return pooled;      // nothing known in it; an honest zero vector
        float[] table = embeddings.values();
        for (int token : tokens) {
            int offset = token * columns;
            for (int d = 0; d < columns; d++) pooled[d] += table[offset + d];
        }
        double norm = 0.0;
        for (int d = 0; d < columns; d++) {
            pooled[d] /= tokens.length;
            norm += (double) pooled[d] * pooled[d];
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int d = 0; d < columns; d++) pooled[d] /= (float) norm;
        }
        return pooled;
    }

    @Override public void close() { }
}

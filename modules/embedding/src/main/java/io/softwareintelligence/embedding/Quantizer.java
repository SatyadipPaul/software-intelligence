package io.softwareintelligence.embedding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Turns a float embedding table into an int8 one, so a model small enough to ship gets smaller.
 *
 * <p>Symmetric, per row. Each token's vector is divided by its own largest magnitude and rounded
 * into the range [-127, 127], and that divisor is kept beside it. Per row rather than per tensor
 * because token vectors differ enormously in magnitude: one scale for the whole matrix would round
 * every rare token's vector towards zero, and rare tokens are exactly the ones carrying the
 * distinguishing meaning in a question.
 *
 * <p>Lossy, therefore measured. Quantising changes every vector, so it can change a ranking, and
 * nothing here assumes it does not: {@link #maximumRelativeError} reports what actually changed and
 * the benchmark re-runs against the quantised file.
 */
public final class Quantizer {

    /** Symmetric int8 keeps -127..127 and leaves -128 unused, so negating a value is exact. */
    private static final int LEVELS = 127;

    private Quantizer() { }

    /**
     * Reads the float weights in {@code source} and writes quantised weights to {@code target},
     * copying {@code vocab.txt} across so the result is a usable model directory on its own.
     *
     * @return the largest relative error introduced on any single weight
     */
    public static double quantize(Path source, Path target) throws IOException {
        SafeTensors.Tensor original = SafeTensors.readMatrix(source.resolve("model.safetensors"), "embeddings");
        int rows = original.rows();
        int columns = original.columns();
        byte[] quantised = new byte[rows * columns];
        float[] scales = new float[rows];
        double worst = 0.0;

        for (int row = 0; row < rows; row++) {
            int offset = row * columns;
            float largest = 0f;
            for (int d = 0; d < columns; d++) largest = Math.max(largest, Math.abs(original.values()[offset + d]));
            // An all-zero row has no scale to speak of. Leaving the scale at zero reproduces it
            // exactly, where any other choice would invent values it never had.
            float scale = largest == 0f ? 0f : largest / LEVELS;
            scales[row] = scale;
            for (int d = 0; d < columns; d++) {
                float value = original.values()[offset + d];
                int level = scale == 0f ? 0 : Math.round(value / scale);
                level = Math.max(-LEVELS, Math.min(LEVELS, level));
                quantised[offset + d] = (byte) level;
                if (largest > 0) worst = Math.max(worst, Math.abs(value - level * scale) / largest);
            }
        }

        Files.createDirectories(target);
        ByteBuffer scaleBytes = ByteBuffer.allocate(rows * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float scale : scales) scaleBytes.putFloat(scale);
        SafeTensors.write(target.resolve("model.safetensors"), List.of(
                new SafeTensors.Written("embeddings", "I8", rows, columns, quantised),
                new SafeTensors.Written("scales", "F32", rows, 1, scaleBytes.array())));

        Path vocabulary = source.resolve("vocab.txt");
        if (Files.isRegularFile(vocabulary)) {
            Files.copy(vocabulary, target.resolve("vocab.txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return worst;
    }

    /**
     * The largest relative error symmetric per-row int8 can introduce, by construction.
     *
     * <p>Rounding to the nearest of 127 levels moves a weight by at most half a level, so the error
     * is bounded at {@code 0.5 / 127} of the row's largest magnitude — under 0.4%. Worth stating
     * because it bounds what quantisation can do to a single weight; what it does to a <em>ranking</em>
     * is a different question, and only a benchmark answers that one.
     */
    public static double maximumRelativeError() { return 0.5 / LEVELS; }
}

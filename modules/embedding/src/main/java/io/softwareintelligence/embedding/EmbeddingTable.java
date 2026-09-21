package io.softwareintelligence.embedding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

/**
 * The lookup table behind a static encoder, stored either as floats or as quantised bytes.
 *
 * <p>Both shapes answer the only question the encoder asks — "add row <i>n</i> to this
 * accumulator" — so quantisation stays invisible above this line. A quantised table is kept
 * quantised in memory and dequantised one row at a time, because expanding it on load would return
 * the memory that quantising it saved and leave only a smaller download.
 */
sealed interface EmbeddingTable {

    int rows();

    int columns();

    /** Adds row {@code index} into {@code accumulator}, which is how pooling reads this. */
    void accumulateInto(int index, float[] accumulator);

    /** How the weights are stored, for anything that reports on a model rather than uses it. */
    String precision();

    /**
     * Loads whichever kind the file holds.
     *
     * <p>An {@code I8} table must carry a {@code scales} tensor beside it, one per row. Per-row
     * scales rather than one for the whole matrix: token vectors differ enormously in magnitude, and
     * a single global scale would quantise the small ones to almost nothing.
     */
    static EmbeddingTable load(Path weights) throws IOException {
        return load(java.nio.file.Files.readAllBytes(weights));
    }

    /** The same, from bytes already in hand. */
    static EmbeddingTable load(byte[] weights) throws IOException {
        SafeTensors.Raw embeddings = SafeTensors.read(weights, "embeddings");
        return switch (embeddings.dtype()) {
            case "F32" -> {
                float[] values = new float[embeddings.rows() * embeddings.columns()];
                ByteBuffer.wrap(embeddings.data()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values);
                yield new Floats(embeddings.rows(), embeddings.columns(), values);
            }
            case "I8" -> {
                SafeTensors.Raw scales = SafeTensors.read(weights, "scales");
                if (scales.rows() * scales.columns() != embeddings.rows()) {
                    throw new IOException("a quantised table needs one scale per row: "
                            + embeddings.rows() + " rows but " + scales.rows() * scales.columns() + " scales");
                }
                float[] perRow = new float[embeddings.rows()];
                ByteBuffer.wrap(scales.data()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(perRow);
                yield new Quantised(embeddings.rows(), embeddings.columns(), embeddings.data(), perRow);
            }
            default -> throw new IOException("unsupported weight type " + embeddings.dtype());
        };
    }

    record Floats(int rows, int columns, float[] values) implements EmbeddingTable {
        @Override public void accumulateInto(int index, float[] accumulator) {
            int offset = index * columns;
            for (int d = 0; d < columns; d++) accumulator[d] += values[offset + d];
        }

        @Override public String precision() { return "f32"; }
    }

    record Quantised(int rows, int columns, byte[] values, float[] scales) implements EmbeddingTable {
        @Override public void accumulateInto(int index, float[] accumulator) {
            int offset = index * columns;
            float scale = scales[index];
            for (int d = 0; d < columns; d++) accumulator[d] += values[offset + d] * scale;
        }

        @Override public String precision() { return "int8"; }
    }
}

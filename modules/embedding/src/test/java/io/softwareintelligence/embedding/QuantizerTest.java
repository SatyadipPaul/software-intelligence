package io.softwareintelligence.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuantizerTest {

    /** A model directory with a float table and a matching vocabulary. */
    private static Path model(Path directory, int rows, int columns, float... values) throws IOException {
        Files.createDirectories(directory);
        ByteBuffer data = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) data.putFloat(value);
        SafeTensors.write(directory.resolve("model.safetensors"),
                List.of(new SafeTensors.Written("embeddings", "F32", rows, columns, data.array())));
        StringBuilder vocabulary = new StringBuilder("[PAD]\n[UNK]\n[CLS]\n[SEP]\n");
        for (int i = 4; i < rows; i++) vocabulary.append("word").append(i).append('\n');
        Files.writeString(directory.resolve("vocab.txt"), vocabulary.toString(), StandardCharsets.UTF_8);
        return directory;
    }

    @Test void a_quantized_table_round_trips_within_the_stated_bound(@TempDir Path directory) throws Exception {
        // Two rows with very different magnitudes, which is the case one global scale would ruin.
        Path source = model(directory.resolve("source"), 2, 4,
                1.0f, -0.5f, 0.25f, 0.125f,
                0.001f, -0.002f, 0.0005f, 0.0015f);
        Quantizer.quantize(source, directory.resolve("target"));

        EmbeddingTable table = EmbeddingTable.load(directory.resolve("target").resolve("model.safetensors"));
        assertEquals("int8", table.precision());
        assertEquals(2, table.rows());
        assertEquals(4, table.columns());

        float[] small = new float[4];
        table.accumulateInto(1, small);
        // The tiny row survives because its scale is its own; under one global scale every value
        // here would round to zero.
        float[] expected = {0.001f, -0.002f, 0.0005f, 0.0015f};
        for (int d = 0; d < 4; d++) {
            assertTrue(Math.abs(small[d] - expected[d]) <= 0.002f * Quantizer.maximumRelativeError() + 1e-9,
                    "component " + d + " drifted to " + small[d]);
        }
    }

    @Test void an_all_zero_row_is_reproduced_exactly(@TempDir Path directory) throws Exception {
        Path source = model(directory.resolve("source"), 2, 3, 0f, 0f, 0f, 1f, 2f, 3f);
        Quantizer.quantize(source, directory.resolve("target"));
        EmbeddingTable table = EmbeddingTable.load(directory.resolve("target").resolve("model.safetensors"));
        float[] zero = new float[3];
        table.accumulateInto(0, zero);
        assertArrayEquals(new float[]{0f, 0f, 0f}, zero, "a row with no magnitude must not gain one");
    }

    @Test void the_vocabulary_travels_with_the_weights(@TempDir Path directory) throws Exception {
        Path source = model(directory.resolve("source"), 5, 2, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f);
        Quantizer.quantize(source, directory.resolve("target"));
        // The output has to be a usable model directory on its own, or it is not a shippable artifact.
        assertTrue(Files.isRegularFile(directory.resolve("target").resolve("vocab.txt")));
        assertTrue(EncoderFactory.open(directory.resolve("target")).dimensions() > 0);
    }

    @Test void quantized_weights_approach_a_quarter_of_the_size(@TempDir Path directory) throws Exception {
        // A realistic aspect ratio, because per-row scales cost four bytes per row however wide the
        // row is: on a 256-column table that is under 2% overhead and the saving is nearly the full
        // 4x, while on an 8-column table it would be 50% and the saving barely 2x. Real static
        // models are 256 to 512 wide.
        int rows = 64;
        int columns = 128;
        float[] values = new float[rows * columns];
        for (int i = 0; i < values.length; i++) values[i] = (float) Math.sin(i);
        Path source = model(directory.resolve("source"), rows, columns, values);
        Quantizer.quantize(source, directory.resolve("target"));
        long before = Files.size(source.resolve("model.safetensors"));
        long after = Files.size(directory.resolve("target").resolve("model.safetensors"));
        assertTrue(after < before / 3, "expected a large reduction, got " + before + " -> " + after);
    }

    @Test void a_quantized_table_without_scales_is_refused(@TempDir Path directory) throws Exception {
        Files.createDirectories(directory);
        SafeTensors.write(directory.resolve("model.safetensors"),
                List.of(new SafeTensors.Written("embeddings", "I8", 2, 3, new byte[6])));
        IOException failure = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> EmbeddingTable.load(directory.resolve("model.safetensors")));
        assertTrue(failure.getMessage().contains("scale"), failure.getMessage());
    }
}

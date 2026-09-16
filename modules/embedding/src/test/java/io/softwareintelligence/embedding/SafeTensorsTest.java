package io.softwareintelligence.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeTensorsTest {

    /** Builds a real safetensors file: 8-byte little-endian header length, JSON header, then data. */
    private static Path write(@TempDir Path directory, String header, float... values) throws IOException {
        byte[] json = header.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(json.length).array());
        out.write(json);
        ByteBuffer data = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) data.putFloat(value);
        out.write(data.array());
        Path file = directory.resolve("model.safetensors");
        Files.write(file, out.toByteArray());
        return file;
    }

    @Test void reads_a_named_matrix_in_row_major_order(@TempDir Path directory) throws Exception {
        Path file = write(directory,
                "{\"embeddings\":{\"dtype\":\"F32\",\"shape\":[2,3],\"data_offsets\":[0,24]}}",
                1f, 2f, 3f, 4f, 5f, 6f);
        SafeTensors.Tensor tensor = SafeTensors.readMatrix(file, "embeddings");
        assertEquals(2, tensor.rows());
        assertEquals(3, tensor.columns());
        assertArrayEquals(new float[]{4f, 5f, 6f}, tensor.row(1));
    }

    @Test void a_missing_tensor_names_what_the_file_actually_holds(@TempDir Path directory) throws Exception {
        Path file = write(directory,
                "{\"weights\":{\"dtype\":\"F32\",\"shape\":[1,1],\"data_offsets\":[0,4]}}", 1f);
        IOException failure = assertThrows(IOException.class, () -> SafeTensors.readMatrix(file, "embeddings"));
        assertTrue(failure.getMessage().contains("weights"), failure.getMessage());
    }

    @Test void a_tensor_that_does_not_fit_its_file_is_refused(@TempDir Path directory) throws Exception {
        // Claims six floats, carries three. Reading it anyway would produce a silently wrong matrix.
        Path file = write(directory,
                "{\"embeddings\":{\"dtype\":\"F32\",\"shape\":[2,3],\"data_offsets\":[0,24]}}", 1f, 2f, 3f);
        assertThrows(IOException.class, () -> SafeTensors.readMatrix(file, "embeddings"));
    }

    @Test void a_non_float_tensor_is_refused_rather_than_misread(@TempDir Path directory) throws Exception {
        Path file = write(directory,
                "{\"embeddings\":{\"dtype\":\"I64\",\"shape\":[1,1],\"data_offsets\":[0,8]}}", 0f, 0f);
        IOException failure = assertThrows(IOException.class, () -> SafeTensors.readMatrix(file, "embeddings"));
        assertTrue(failure.getMessage().contains("I64"), failure.getMessage());
    }

    @Test void the_header_parser_handles_nesting_escapes_and_exponents() throws Exception {
        Object parsed = new SafeTensors.MiniJson(
                "{\"a\":[1,2.5,-3e2],\"b\":{\"c\":\"x\\\"y\",\"d\":null},\"e\":true}").value();
        java.util.Map<?, ?> map = (java.util.Map<?, ?>) parsed;
        assertEquals(-300.0, ((java.util.List<?>) map.get("a")).get(2));
        assertEquals("x\"y", ((java.util.Map<?, ?>) map.get("b")).get("c"));
        assertEquals(Boolean.TRUE, map.get("e"));
    }
}

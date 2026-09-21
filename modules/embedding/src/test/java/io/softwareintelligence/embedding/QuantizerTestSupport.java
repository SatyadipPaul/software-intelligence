package io.softwareintelligence.embedding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Builds a small but genuine model directory, so tests exercise the real loading path. */
final class QuantizerTestSupport {
    private QuantizerTestSupport() { }

    static void writeTinyModel(Path directory, int rows, int columns) throws IOException {
        Files.createDirectories(directory);
        ByteBuffer data = ByteBuffer.allocate(rows * columns * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < rows * columns; i++) data.putFloat((float) Math.cos(i));
        SafeTensors.write(directory.resolve("model.safetensors"),
                List.of(new SafeTensors.Written("embeddings", "F32", rows, columns, data.array())));
        StringBuilder vocabulary = new StringBuilder("[PAD]\n[UNK]\n[CLS]\n[SEP]\n");
        for (int i = 4; i < rows; i++) vocabulary.append("word").append(i).append('\n');
        Files.writeString(directory.resolve("vocab.txt"), vocabulary.toString(), StandardCharsets.UTF_8);
    }
}

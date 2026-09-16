package io.softwareintelligence.embedding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads one named float tensor out of a {@code .safetensors} file.
 *
 * <p>The format is deliberately trivial: eight bytes of little-endian header length, that many bytes
 * of JSON describing each tensor's dtype, shape and byte range, then the raw data. A static
 * embedding model is a single matrix in that file, so reading it needs no tensor library and no
 * native code — which is the property that lets such a model ship inside a plain Java library.
 */
public final class SafeTensors {

    /** Refuses a header larger than this rather than allocating whatever the file claims. */
    private static final long MAX_HEADER_BYTES = 64L * 1024 * 1024;

    public record Tensor(int rows, int columns, float[] values) {
        public float[] row(int index) {
            float[] row = new float[columns];
            System.arraycopy(values, index * columns, row, 0, columns);
            return row;
        }
    }

    /** One tensor as stored: its declared type, its shape, and its bytes, undecoded. */
    public record Raw(String dtype, int rows, int columns, byte[] data) {
        /** Width in bytes of one element of this tensor's type. */
        public int elementBytes() { return bytesPer(dtype); }
    }

    private SafeTensors() { }

    private static int bytesPer(String dtype) {
        return switch (dtype) {
            case "F32" -> 4;
            case "I8", "U8" -> 1;
            default -> -1;
        };
    }

    /** Loads a two-dimensional {@code F32} tensor by name. */
    public static Tensor readMatrix(Path file, String name) throws IOException {
        Raw raw = read(Files.readAllBytes(file), name);
        if (!"F32".equals(raw.dtype())) throw new IOException("expected an F32 tensor, found " + raw.dtype());
        float[] values = new float[raw.rows() * raw.columns()];
        ByteBuffer.wrap(raw.data()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values);
        return new Tensor(raw.rows(), raw.columns(), values);
    }

    /**
     * Loads a two-dimensional tensor by name without decoding it.
     *
     * <p>Undecoded because an int8 table is kept as int8: expanding a quantised matrix to floats on
     * load would give back the memory that quantising it was meant to save, leaving only the smaller
     * download.
     */
    public static Raw read(Path file, String name) throws IOException {
        return read(Files.readAllBytes(file), name);
    }

    /** The same, from bytes already in hand — a jar entry, say. */
    public static Raw read(byte[] bytes, String name) throws IOException {
        if (bytes.length < 8) throw new IOException("not a safetensors file");
        long headerLength = ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        if (headerLength <= 0 || headerLength > MAX_HEADER_BYTES || 8 + headerLength > bytes.length) {
            throw new IOException("safetensors header length is implausible: " + headerLength);
        }
        String header = new String(bytes, 8, (int) headerLength, StandardCharsets.UTF_8);
        Object parsed = new MiniJson(header).value();
        if (!(parsed instanceof Map<?, ?> entries)) throw new IOException("safetensors header is not an object");
        Object entry = entries.get(name);
        if (!(entry instanceof Map<?, ?> tensor)) {
            throw new IOException("safetensors file has no tensor called " + name + "; it has " + entries.keySet());
        }
        String dtype = String.valueOf(tensor.get("dtype"));
        int width = bytesPer(dtype);
        if (width < 0) throw new IOException("unsupported tensor type " + dtype + " for " + name);
        List<?> shape = (List<?>) tensor.get("shape");
        if (shape == null || shape.isEmpty() || shape.size() > 2) {
            throw new IOException("expected a one- or two-dimensional tensor");
        }
        int rows = ((Number) shape.get(0)).intValue();
        int columns = shape.size() == 2 ? ((Number) shape.get(1)).intValue() : 1;
        List<?> offsets = (List<?>) tensor.get("data_offsets");
        long start = ((Number) offsets.get(0)).longValue() + 8 + headerLength;
        long end = ((Number) offsets.get(1)).longValue() + 8 + headerLength;
        if (end > bytes.length || start < 0 || end < start
                || end - start != (long) rows * columns * width) {
            throw new IOException("tensor " + name + " does not fit the file it claims to be in");
        }
        byte[] data = new byte[(int) (end - start)];
        System.arraycopy(bytes, (int) start, data, 0, data.length);
        return new Raw(dtype, rows, columns, data);
    }

    /** Writes tensors in the order given, which is the order their byte ranges appear. */
    public static void write(Path file, List<Written> tensors) throws IOException {
        StringBuilder header = new StringBuilder("{");
        long offset = 0;
        for (int i = 0; i < tensors.size(); i++) {
            Written tensor = tensors.get(i);
            if (i > 0) header.append(',');
            header.append('"').append(tensor.name()).append("\":{\"dtype\":\"").append(tensor.dtype())
                    .append("\",\"shape\":[").append(tensor.rows());
            if (tensor.columns() > 1) header.append(',').append(tensor.columns());
            header.append("],\"data_offsets\":[").append(offset).append(',')
                    .append(offset + tensor.data().length).append("]}");
            offset += tensor.data().length;
        }
        header.append('}');
        byte[] json = header.toString().getBytes(StandardCharsets.UTF_8);
        try (java.io.OutputStream out = Files.newOutputStream(file)) {
            out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(json.length).array());
            out.write(json);
            for (Written tensor : tensors) out.write(tensor.data());
        }
    }

    /** A tensor to write: its name, type, shape and bytes. */
    public record Written(String name, String dtype, int rows, int columns, byte[] data) { }

    /**
     * Just enough JSON to read a safetensors header.
     *
     * <p>Written rather than depended on: this module's whole point is that it adds no mandatory
     * dependency, and pulling a JSON library in to read one machine-generated header would undo
     * that for eighty lines of code.
     */
    static final class MiniJson {
        private final String text;
        private int at;

        MiniJson(String text) { this.text = text; }

        Object value() throws IOException {
            skipSpace();
            if (at >= text.length()) throw new IOException("unexpected end of JSON");
            char c = text.charAt(at);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() throws IOException {
            Map<String, Object> entries = new LinkedHashMap<>();
            at++;                                   // past '{'
            skipSpace();
            if (peek() == '}') { at++; return entries; }
            while (true) {
                skipSpace();
                String key = string();
                skipSpace();
                expect(':');
                entries.put(key, value());
                skipSpace();
                char c = next();
                if (c == '}') return entries;
                if (c != ',') throw new IOException("expected , or } at " + at);
            }
        }

        private List<Object> array() throws IOException {
            List<Object> items = new ArrayList<>();
            at++;                                   // past '['
            skipSpace();
            if (peek() == ']') { at++; return items; }
            while (true) {
                items.add(value());
                skipSpace();
                char c = next();
                if (c == ']') return items;
                if (c != ',') throw new IOException("expected , or ] at " + at);
            }
        }

        private String string() throws IOException {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (at >= text.length()) throw new IOException("unterminated string");
                char c = text.charAt(at++);
                if (c == '"') return out.toString();
                if (c != '\\') { out.append(c); continue; }
                char escape = text.charAt(at++);
                switch (escape) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> { out.append((char) Integer.parseInt(text.substring(at, at + 4), 16)); at += 4; }
                    default -> out.append(escape);   // covers \" \\ \/
                }
            }
        }

        private Double number() throws IOException {
            int start = at;
            while (at < text.length() && "+-.eE0123456789".indexOf(text.charAt(at)) >= 0) at++;
            if (start == at) throw new IOException("expected a value at " + at);
            return Double.valueOf(text.substring(start, at));
        }

        private Object literal(String word, Object result) throws IOException {
            if (!text.startsWith(word, at)) throw new IOException("expected " + word + " at " + at);
            at += word.length();
            return result;
        }

        private void skipSpace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
        }

        private char peek() throws IOException {
            if (at >= text.length()) throw new IOException("unexpected end of JSON");
            return text.charAt(at);
        }

        private char next() throws IOException {
            char c = peek();
            at++;
            return c;
        }

        private void expect(char expected) throws IOException {
            if (next() != expected) throw new IOException("expected " + expected + " at " + (at - 1));
        }
    }
}

package io.softwareintelligence.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict JSON, read and written, for the server's protocol messages.
 *
 * <p>The graph reader in {@code model} is deliberately a parser for one document shape. The server
 * speaks JSON-RPC to a client this project did not write, so it needs the general case - but not a
 * library: everything on the distribution path is audited and allow-listed, and a few hundred lines
 * that do only what RFC 8259 says are easier to keep honest than a dependency that does far more.
 *
 * <p>Values map to {@code Map<String, Object>} (insertion-ordered), {@code List<Object>},
 * {@code String}, {@code Long} for integers that fit, {@code Double} otherwise, {@code Boolean},
 * and {@code null}.
 *
 * <p>Strict means rejecting what a lenient parser would guess at: trailing commas, comments,
 * single quotes, leading zeros, unescaped control characters, duplicate keys, and anything after
 * the value. A message that is not JSON is answered with a parse error rather than a guess about
 * what it meant.
 */
final class JsonCodec {

    /** Past this, the input is hostile or broken, and recursing further risks the stack. */
    private static final int MAX_DEPTH = 64;

    private final String text;
    private int at;

    private JsonCodec(String text) {
        this.text = text;
    }

    static final class ParseException extends RuntimeException {
        ParseException(String message, int offset) {
            super(message + " at offset " + offset);
        }
    }

    static Object parse(String text) {
        JsonCodec reader = new JsonCodec(text);
        reader.whitespace();
        Object value = reader.value(0);
        reader.whitespace();
        if (reader.at != text.length()) throw new ParseException("unexpected content after the value", reader.at);
        return value;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw new ParseException("nested more than " + MAX_DEPTH + " deep", at);
        if (at >= text.length()) throw new ParseException("unexpected end of input", at);
        char c = text.charAt(at);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) yield number();
                throw new ParseException("unexpected character '" + c + "'", at);
            }
        };
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> result = new LinkedHashMap<>();
        at++;
        whitespace();
        if (peek() == '}') { at++; return result; }
        while (true) {
            whitespace();
            if (peek() != '"') throw new ParseException("expected a string key", at);
            int keyAt = at;
            String key = string();
            if (result.containsKey(key)) throw new ParseException("duplicate key \"" + key + "\"", keyAt);
            whitespace();
            expect(':');
            whitespace();
            result.put(key, value(depth + 1));
            whitespace();
            char next = next();
            if (next == '}') return result;
            if (next != ',') throw new ParseException("expected ',' or '}'", at - 1);
        }
    }

    private List<Object> array(int depth) {
        List<Object> result = new ArrayList<>();
        at++;
        whitespace();
        if (peek() == ']') { at++; return result; }
        while (true) {
            whitespace();
            result.add(value(depth + 1));
            whitespace();
            char next = next();
            if (next == ']') return result;
            if (next != ',') throw new ParseException("expected ',' or ']'", at - 1);
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) throw new ParseException("unterminated string", at);
            char c = text.charAt(at++);
            if (c == '"') return out.toString();
            if (c < 0x20) throw new ParseException("unescaped control character in a string", at - 1);
            if (c != '\\') { out.append(c); continue; }
            if (at >= text.length()) throw new ParseException("unterminated escape", at);
            char escaped = text.charAt(at++);
            switch (escaped) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                // Surrogate pairs arrive as two escapes and are appended as the two UTF-16 units
                // they already are, so no reassembly is needed.
                case 'u' -> out.append(hex4());
                default -> throw new ParseException("invalid escape '\\" + escaped + "'", at - 1);
            }
        }
    }

    private char hex4() {
        if (at + 4 > text.length()) throw new ParseException("truncated \\u escape", at);
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(text.charAt(at++), 16);
            if (digit < 0) throw new ParseException("invalid hex digit in \\u escape", at - 1);
            value = value * 16 + digit;
        }
        return (char) value;
    }

    private Object number() {
        int start = at;
        if (peek() == '-') at++;
        if (peek() == '0') {
            at++;
            if (Character.isDigit(peek())) throw new ParseException("leading zero in a number", at);
        } else if (Character.isDigit(peek())) {
            while (Character.isDigit(peek())) at++;
        } else {
            throw new ParseException("expected a digit", at);
        }
        boolean integral = true;
        if (peek() == '.') {
            integral = false;
            at++;
            if (!Character.isDigit(peek())) throw new ParseException("expected a digit after '.'", at);
            while (Character.isDigit(peek())) at++;
        }
        if (peek() == 'e' || peek() == 'E') {
            integral = false;
            at++;
            if (peek() == '+' || peek() == '-') at++;
            if (!Character.isDigit(peek())) throw new ParseException("expected a digit in the exponent", at);
            while (Character.isDigit(peek())) at++;
        }
        String literal = text.substring(start, at);
        if (integral) {
            try {
                return Long.parseLong(literal);
            } catch (NumberFormatException tooLarge) {
                // Still a valid JSON number, just not a long.
            }
        }
        return Double.parseDouble(literal);
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, at)) throw new ParseException("unexpected token", at);
        at += word.length();
        return value;
    }

    private void whitespace() {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
            at++;
        }
    }

    private char peek() {
        return at < text.length() ? text.charAt(at) : '\0';
    }

    private char next() {
        if (at >= text.length()) throw new ParseException("unexpected end of input", at);
        return text.charAt(at++);
    }

    private void expect(char c) {
        if (next() != c) throw new ParseException("expected '" + c + "'", at - 1);
    }

    /**
     * Writes a value as compact JSON on one line.
     *
     * <p>One line is a protocol requirement, not a style: on stdio each message is terminated by a
     * newline, so a raw newline inside one would end it early. Every control character is escaped,
     * which guarantees there is none.
     */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case String s -> quote(s, out);
            case Boolean b -> out.append(b);
            case Integer i -> out.append(i);
            case Long l -> out.append(l);
            case Double d -> {
                if (d.isNaN() || d.isInfinite()) throw new IllegalArgumentException("JSON has no " + d);
                out.append(d);
            }
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    quote(String.valueOf(entry.getKey()), out);
                    out.append(':');
                    write(entry.getValue(), out);
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) out.append(',');
                    write(list.get(i), out);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("cannot write " + value.getClass().getName() + " as JSON");
        }
    }

    private static void quote(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}

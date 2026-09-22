package io.softwareintelligence.cli;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The protocol's JSON: what must be read, what must be refused, and what may never be written. */
final class JsonCodecTest {

    @Test void reads_every_json_type() {
        Object parsed = JsonCodec.parse("""
                {"s":"text","i":42,"n":-7,"d":1.5,"e":2e3,"t":true,"f":false,"z":null,"a":[1,"two",[]],"o":{}}""");
        Map<?, ?> map = (Map<?, ?>) parsed;
        assertEquals("text", map.get("s"));
        assertEquals(42L, map.get("i"));
        assertEquals(-7L, map.get("n"));
        assertEquals(1.5, map.get("d"));
        assertEquals(2000.0, map.get("e"));
        assertEquals(true, map.get("t"));
        assertEquals(false, map.get("f"));
        assertTrue(map.containsKey("z"));
        assertNull(map.get("z"));
        assertEquals(List.of(1L, "two", List.of()), map.get("a"));
        assertEquals(Map.of(), map.get("o"));
    }

    @Test void reads_escapes_including_surrogate_pairs() {
        assertEquals("a\"b\\c/d\b\f\n\r\t", JsonCodec.parse("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\""));
        assertEquals("é", JsonCodec.parse("\"\\u00e9\""));
        assertEquals("😀", JsonCodec.parse("\"\\ud83d\\ude00\""));
    }

    @Test void keeps_key_order() {
        Map<?, ?> map = (Map<?, ?>) JsonCodec.parse("{\"z\":1,\"a\":2,\"m\":3}");
        assertEquals(List.of("z", "a", "m"), List.copyOf(map.keySet()));
    }

    @Test void a_number_too_large_for_a_long_is_still_a_number() {
        assertEquals(1.0E20, JsonCodec.parse("100000000000000000000"));
    }

    @Test void refuses_what_a_lenient_parser_would_guess_at() {
        for (String malformed : List.of(
                "{\"a\":1,}",            // trailing comma
                "[1,2,]",
                "{'a':1}",               // single quotes
                "{\"a\":01}",            // leading zero
                "{\"a\":1}{",            // content after the value
                "{\"a\":1,\"a\":2}",     // duplicate key: which one did the sender mean?
                "\"line\nbreak\"",       // unescaped control character
                "{\"a\" 1}",
                "[1 2]",
                "tru",
                "-",
                "1.",
                "\"\\x\"",               // invalid escape
                "\"\\u12\"",             // truncated unicode escape
                "\"unterminated",
                "",
                "// comment\n{}")) {
            assertThrows(JsonCodec.ParseException.class, () -> JsonCodec.parse(malformed), malformed);
        }
    }

    @Test void refuses_nesting_deep_enough_to_threaten_the_stack() {
        String deep = "[".repeat(10_000) + "]".repeat(10_000);
        assertThrows(JsonCodec.ParseException.class, () -> JsonCodec.parse(deep));
    }

    @Test void writes_on_one_line_whatever_the_text_contains() {
        // On stdio a raw newline ends the message early, so this is a protocol requirement.
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("text", "line one\nline two\r\n\ttabbed \u0001 control");
        String written = JsonCodec.write(message);
        assertFalse(written.contains("\n"), written);
        assertFalse(written.contains("\r"), written);
        assertTrue(written.contains("\\u0001"), written);
    }

    @Test void what_is_written_reads_back_unchanged() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("jsonrpc", "2.0");
        original.put("id", 7L);
        original.put("list", List.of("a\"quoted\"", 1L, 2.5, true, false));
        original.put("nothing", null);
        original.put("nested", Map.of("k", "v\\w"));
        assertEquals(original, JsonCodec.parse(JsonCodec.write(original)));
    }

    @Test void refuses_to_write_what_json_cannot_express() {
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.write(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> JsonCodec.write(new Object()));
    }
}

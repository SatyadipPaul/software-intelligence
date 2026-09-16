package io.softwareintelligence.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A tokenizer that disagrees with the one the model was trained on produces vectors of the right
 * shape and the wrong meaning, which no downstream test would catch. These pin the behaviour that
 * matters against a small hand-built vocabulary where the expected ids can be read off by eye.
 */
class WordPieceTokenizerTest {

    /** ids: [PAD]=0 [UNK]=1 [CLS]=2 [SEP]=3 then the rest in order. */
    private static final List<String> VOCABULARY = List.of(
            "[PAD]", "[UNK]", "[CLS]", "[SEP]",
            "test", "switch", "##es", "off", "disabled", "the", "run", "##ner", "a", "-", ".", "cafe");

    private WordPieceTokenizer tokenizer(@TempDir Path directory) throws Exception {
        Path vocabulary = directory.resolve("vocab.txt");
        Files.writeString(vocabulary, String.join("\n", VOCABULARY) + "\n", StandardCharsets.UTF_8);
        return WordPieceTokenizer.fromVocabulary(vocabulary);
    }

    @Test void wraps_every_encoding_in_the_classify_and_separate_tokens(@TempDir Path directory) throws Exception {
        assertArrayEquals(new int[]{2, 4, 3}, tokenizer(directory).encode("test", 128));
    }

    @Test void splits_a_word_into_subwords_with_the_continuing_prefix(@TempDir Path directory) throws Exception {
        // "switches" is not in the vocabulary; "switch" + "##es" is.
        assertArrayEquals(new int[]{2, 5, 6, 3}, tokenizer(directory).encode("switches", 128));
    }

    @Test void a_word_with_an_unmatched_remainder_becomes_unknown_whole(@TempDir Path directory) throws Exception {
        // "switchy" starts with a vocabulary entry, and the reference implementation still emits
        // [UNK] for the whole word rather than the prefix it managed to match. Emitting "switch"
        // here would feed the model a word the text does not contain.
        assertArrayEquals(new int[]{2, 1, 3}, tokenizer(directory).encode("switchy", 128));
    }

    @Test void lowercases_and_strips_accents(@TempDir Path directory) throws Exception {
        assertArrayEquals(tokenizer(directory).encode("cafe", 128), tokenizer(directory).encode("CAFÉ", 128));
    }

    @Test void punctuation_is_split_off_as_its_own_token(@TempDir Path directory) throws Exception {
        assertArrayEquals(new int[]{2, 4, 14, 3}, tokenizer(directory).encode("test.", 128));
        assertArrayEquals(new int[]{2, 4, 13, 4, 3}, tokenizer(directory).encode("test-test", 128));
    }

    @Test void truncation_keeps_room_for_the_separate_token(@TempDir Path directory) throws Exception {
        int[] encoded = tokenizer(directory).encode("test test test test test", 4);
        assertEquals(4, encoded.length);
        assertEquals(2, encoded[0]);
        assertEquals(3, encoded[encoded.length - 1], "the [SEP] token must survive truncation");
    }

    @Test void a_vocabulary_without_the_special_tokens_is_refused(@TempDir Path directory) throws Exception {
        Path vocabulary = directory.resolve("bare.txt");
        Files.writeString(vocabulary, "test\nswitch\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> WordPieceTokenizer.fromVocabulary(vocabulary));
    }
}

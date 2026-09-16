package io.softwareintelligence.embedding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BERT uncased WordPiece, in plain Java.
 *
 * <p>Written rather than pulled in because every off-the-shelf Java tokenizer for this format is a
 * JNI binding, and a per-platform native library is a heavy thing to inflict on consumers of a
 * library that only wants to turn a sentence into integers. The algorithm is small and fixed: clean,
 * lowercase, strip accents, split on whitespace with punctuation as its own token, then greedy
 * longest-match-first subwording where every piece after the first carries {@code ##}.
 *
 * <p>Matching the reference implementation matters more here than it looks. A tokenizer that splits
 * differently from the one the model was trained with produces embeddings that are quietly wrong
 * rather than obviously broken — the vectors still have the right shape and the wrong meaning.
 */
public final class WordPieceTokenizer {

    private static final String CONTINUING_PREFIX = "##";
    /** Longer than this and BERT gives up on the word rather than subwording it indefinitely. */
    private static final int MAX_CHARS_PER_WORD = 100;

    private final Map<String, Integer> vocabulary;
    private final int classify;
    private final int separate;
    private final int unknown;

    private WordPieceTokenizer(Map<String, Integer> vocabulary) {
        this.vocabulary = vocabulary;
        this.classify = require(vocabulary, "[CLS]");
        this.separate = require(vocabulary, "[SEP]");
        this.unknown = require(vocabulary, "[UNK]");
    }

    private static int require(Map<String, Integer> vocabulary, String token) {
        Integer id = vocabulary.get(token);
        if (id == null) throw new IllegalArgumentException("vocabulary is missing the " + token + " token");
        return id;
    }

    /** Reads a {@code vocab.txt}: one token per line, the line number being the id. */
    public static WordPieceTokenizer fromVocabulary(Path vocabularyFile) throws IOException {
        List<String> lines = Files.readAllLines(vocabularyFile, StandardCharsets.UTF_8);
        Map<String, Integer> vocabulary = new HashMap<>(lines.size() * 2);
        for (int i = 0; i < lines.size(); i++) vocabulary.putIfAbsent(lines.get(i), i);
        return new WordPieceTokenizer(vocabulary);
    }

    public int vocabularySize() { return vocabulary.size(); }

    /**
     * Token ids for one text, wrapped in {@code [CLS]} and {@code [SEP]} and truncated to
     * {@code maxLength} including both.
     */
    public int[] encode(String text, int maxLength) {
        if (maxLength < 2) throw new IllegalArgumentException("maxLength must leave room for [CLS] and [SEP]");
        List<Integer> ids = new ArrayList<>();
        ids.add(classify);
        for (String word : split(text)) {
            if (ids.size() >= maxLength - 1) break;
            ids.addAll(subwords(word));
        }
        if (ids.size() > maxLength - 1) ids = ids.subList(0, maxLength - 1);
        ids.add(separate);
        int[] encoded = new int[ids.size()];
        for (int i = 0; i < encoded.length; i++) encoded[i] = ids.get(i);
        return encoded;
    }

    /**
     * Token ids for one text with the special tokens and the unknowns left out.
     *
     * <p>What a static model wants. There is no network to give {@code [CLS]} a meaning, so a
     * static model pools the content tokens and nothing else — including an {@code [UNK]} would
     * average in one fixed vector for every word the vocabulary does not know, which drags every
     * text that has one towards the same place.
     */
    public int[] encodeContent(String text, int maxLength) {
        List<Integer> ids = new ArrayList<>();
        for (String word : split(text)) {
            if (ids.size() >= maxLength) break;
            for (int id : subwords(word)) {
                if (id != unknown) ids.add(id);
            }
        }
        if (ids.size() > maxLength) ids = ids.subList(0, maxLength);
        int[] encoded = new int[ids.size()];
        for (int i = 0; i < encoded.length; i++) encoded[i] = ids.get(i);
        return encoded;
    }

    /** Lowercases, strips accents, and splits on whitespace with each punctuation mark on its own. */
    private static List<String> split(String text) {
        String folded = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{Mn}+", "");
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < folded.length(); i++) {
            char character = folded.charAt(i);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                flush(words, word);
            } else if (isPunctuation(character)) {
                flush(words, word);
                words.add(String.valueOf(character));
            } else {
                word.append(character);
            }
        }
        flush(words, word);
        return words;
    }

    private static void flush(List<String> words, StringBuilder word) {
        if (word.length() > 0) {
            words.add(word.toString());
            word.setLength(0);
        }
    }

    /** BERT treats every ASCII symbol as punctuation, not only what Unicode says is punctuation. */
    private static boolean isPunctuation(char character) {
        if ((character >= 33 && character <= 47) || (character >= 58 && character <= 64)
                || (character >= 91 && character <= 96) || (character >= 123 && character <= 126)) return true;
        int type = Character.getType(character);
        return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION;
    }

    /**
     * Greedy longest-match-first from the left. A word with any unmatched remainder is emitted whole
     * as {@code [UNK]} rather than partially — that is what the reference implementation does, and
     * emitting the prefix pieces instead would silently change the input the model sees.
     */
    private List<Integer> subwords(String word) {
        if (word.length() > MAX_CHARS_PER_WORD) return List.of(unknown);
        List<Integer> pieces = new ArrayList<>();
        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            Integer matched = null;
            while (start < end) {
                String piece = (start == 0 ? "" : CONTINUING_PREFIX) + word.substring(start, end);
                Integer id = vocabulary.get(piece);
                if (id != null) { matched = id; break; }
                end--;
            }
            if (matched == null) return List.of(unknown);
            pieces.add(matched);
            start = end;
        }
        return pieces;
    }
}

package io.softwareintelligence.analyzer.java;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What "bounded, deduplicated digest" means, shared by both Tier 0 sources that are not Javadoc. */
class VocabularyDigestTest {

    @Test void widest_first_and_a_phrase_that_says_nothing_new_is_dropped() {
        String digest = VocabularyDigest.of(List.of(
                "add all with null", "add all with null again", "reject malformed cursor"));

        // "add all with null" is wholly contained in what was already said; listing it would spend
        // budget saying the same thing twice.
        assertEquals("add all with null again; reject malformed cursor", digest);
    }

    @Test void the_budget_holds_however_much_is_offered() {
        List<String> phrases = new ArrayList<>();
        for (int i = 0; i < 500; i++) phrases.add("scenario number " + i + " handled correctly " + (char) ('a' + i % 26));

        assertTrue(VocabularyDigest.of(phrases).length() <= VocabularyDigest.BUDGET);
    }

    @Test void a_long_phrase_that_does_not_fit_does_not_block_a_short_one_that_does() {
        String longest = "a".repeat(VocabularyDigest.BUDGET - 4) + " tail";

        String digest = VocabularyDigest.of(List.of(longest, "short enough"), 40);

        assertEquals("short enough", digest);
    }

    @Test void blanks_and_duplicates_are_not_content() {
        assertEquals("only this", VocabularyDigest.of(List.of("only this", "  ", "only this", "")));
    }
}

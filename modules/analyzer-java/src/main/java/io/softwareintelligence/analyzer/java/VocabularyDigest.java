package io.softwareintelligence.analyzer.java;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns many short human phrases about one symbol into a few that fit on a card.
 *
 * <p>Both Tier 0 sources that are not Javadoc — test names and commit subjects — arrive as a pile of
 * sentences of wildly varying usefulness, and both need the same thing done to them: deduplicate,
 * keep what adds vocabulary, and stop at a budget. Doing it once means the two sources cannot drift
 * into disagreeing about what a digest is.
 *
 * <p>The selection is greedy on <em>new words</em> rather than on order or recency. Taking the first
 * few would spend the whole budget on {@code addAllWithNull}, {@code addAllWithEmpty},
 * {@code addAllWithDuplicate}; what these attributes exist to add is vocabulary, so what the
 * selection maximises is vocabulary added per character.
 */
final class VocabularyDigest {

    /**
     * The character budget for one symbol's digest, matching the Javadoc first-sentence cap.
     *
     * <p>Same reason as there: a card that grew with the number of tests, or the number of commits,
     * would reintroduce the length noise BM25 normalisation then has to undo, and a symbol with two
     * hundred of either would outrank a symbol with two on volume alone.
     */
    static final int BUDGET = 240;

    private VocabularyDigest() { }

    /** @param phrases already normalised; blanks are ignored and duplicates collapse */
    static String of(Collection<String> phrases, int budget) {
        Set<String> remaining = new TreeSet<>();
        for (String phrase : phrases) if (phrase != null && !phrase.isBlank()) remaining.add(phrase.trim());

        Set<String> said = new HashSet<>();
        List<String> chosen = new ArrayList<>();
        int used = 0;
        while (!remaining.isEmpty()) {
            String best = null;
            int bestGain = 0;
            for (String phrase : remaining) {
                int gain = 0;
                for (String word : phrase.split(" ")) if (!said.contains(word)) gain++;
                if (gain > bestGain) {
                    bestGain = gain;
                    best = phrase;
                }
            }
            // Nothing left that says anything new. A phrase wholly contained in what was already
            // chosen would spend budget repeating it.
            if (best == null) break;
            remaining.remove(best);
            // Skipped rather than truncated, and the loop continues: a long phrase that does not fit
            // must not stop a short one that does.
            if (used + best.length() + 2 > budget) continue;
            chosen.add(best);
            used += best.length() + 2;
            said.addAll(List.of(best.split(" ")));
        }
        return String.join("; ", chosen);
    }

    static String of(Collection<String> phrases) {
        return of(phrases, BUDGET);
    }
}

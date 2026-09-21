package io.softwareintelligence.queryengine;

import io.softwareintelligence.model.TestSources;

/**
 * What retrieval does about test code, once, for everything that needs to know.
 *
 * <p>The predicate itself moved to {@link TestSources} when a third caller appeared — the analyzer
 * pass that reads test names as vocabulary has to agree with retrieval about which side of the line
 * a type is on. What stays here is the part that is retrieval's own opinion: how much of a test
 * symbol's score survives.
 */
final class TestCode {

    /**
     * How much of a test symbol's score survives.
     *
     * <p>Demoted rather than removed. A test is sometimes exactly what a reader wants — "what covers
     * this?" — so it has to stay reachable; it is just never the answer when production code fits.
     * The factor matches the one {@link BranchEnrichment} already applies, so the codebase holds one
     * opinion about this rather than two.
     */
    static final double WEIGHT = 0.1;

    private TestCode() { }

    /**
     * @param name the symbol's name, qualified or not
     * @param file the file it was declared in, as recorded in its provenance
     */
    static boolean is(String name, String file) {
        return TestSources.is(name, file);
    }

    /**
     * Demotes a similarity without changing the order of anything else.
     *
     * <p>Multiplying a signed score is not safe on its own: a negative similarity multiplied by a
     * factor below one moves <em>up</em>. Taking the smaller of the two keeps the demotion monotone
     * whatever the sign, which matters because cosine similarity is genuinely signed.
     */
    static double demote(double score) {
        return Math.min(score, score * WEIGHT);
    }

    /** The last dotted or slashed segment, lowercased. Shared so callers agree on what a name is. */
    static String lastSegment(String name) {
        return TestSources.lastSegment(name);
    }
}

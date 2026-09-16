package io.softwareintelligence.queryengine;

import java.util.Locale;

/**
 * Whether a symbol is test code, decided once for everything that needs to know.
 *
 * <p>Two places already needed this and had drifted apart: enrichment ranking knew about
 * {@code src/test} and {@code src/testFixtures}, while the analyzer also knew about {@code src/it}
 * and the two spellings of an integration-test root. This is the union, so a branch and a vector
 * agree about what a test is.
 *
 * <p>The path is asked first and the name second, because a repository may legitimately ship a
 * production class called {@code TestSupport} — and, the other way round, a test class is often
 * named after the thing it exercises with no hint in the name at all.
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
        String path = '/' + (file == null ? "" : file.replace('\\', '/'));
        if (path.contains("/src/test/") || path.contains("/src/testFixtures/")
                || path.contains("/src/it/") || path.contains("/src/integration-test/")
                || path.contains("/src/integrationTest/")) {
            return true;
        }
        String simple = lastSegment(name);
        return simple.endsWith("test") || simple.endsWith("tests")
                || simple.endsWith("testcase") || simple.endsWith("it");
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
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        int slash = name.lastIndexOf('/');
        int cut = Math.max(dot, slash);
        return (cut < 0 ? name : name.substring(cut + 1)).toLowerCase(Locale.ROOT);
    }
}

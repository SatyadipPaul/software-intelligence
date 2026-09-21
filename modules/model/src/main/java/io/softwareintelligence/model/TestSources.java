package io.softwareintelligence.model;

import java.util.Locale;

/**
 * Whether a symbol is test code, decided once for everything that needs to know.
 *
 * <p>Three places need this answer and two of them had already drifted apart: retrieval demotes
 * test symbols, enrichment ranking skips them, and test vocabulary has to know which side of the
 * line a type is on before it can describe it. The predicate lives here, in the module every layer
 * depends on, so a branch, a vector and an analyzer pass agree about what a test is.
 *
 * <p>The path is asked first and the name second, because a repository may legitimately ship a
 * production class called {@code TestSupport} — and, the other way round, a test class is often
 * named after the thing it exercises with no hint in the name at all.
 */
public final class TestSources {

    private TestSources() { }

    /**
     * @param name the symbol's name, qualified or not
     * @param file the file it was declared in, as recorded in its provenance
     */
    public static boolean is(String name, String file) {
        if (inTestTree(file)) return true;
        String simple = lastSegment(name);
        return simple.endsWith("test") || simple.endsWith("tests")
                || simple.endsWith("testcase") || simple.endsWith("it");
    }

    /** The path half of the question on its own, for callers that must not guess from a name. */
    public static boolean inTestTree(String file) {
        String path = '/' + (file == null ? "" : file.replace('\\', '/'));
        return path.contains("/src/test/") || path.contains("/src/testFixtures/")
                || path.contains("/src/it/") || path.contains("/src/integration-test/")
                || path.contains("/src/integrationTest/");
    }

    /** The last dotted or slashed segment, lowercased. Shared so callers agree on what a name is. */
    public static String lastSegment(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        int slash = name.lastIndexOf('/');
        int cut = Math.max(dot, slash);
        return (cut < 0 ? name : name.substring(cut + 1)).toLowerCase(Locale.ROOT);
    }
}

package io.softwareintelligence.model;

/**
 * The attribute keys more than one module reads.
 *
 * <p>Most attributes are written and read in the same layer and are spelled where they are used.
 * These two are not: they carry the Tier 0 prose — the human-language text a Java repository
 * already contains — from the analyzer that extracts it to every index that has to decide what a
 * symbol is <em>about</em>. A key spelled in four modules is a key that will be misspelled in one.
 */
public final class Attributes {

    /** The first sentence of a declaration's Javadoc, capped. Written by the Java analyzer. */
    public static final String DOC = "doc";

    /**
     * A digest of the test names that exercise this symbol, capped. Written by the test-vocabulary
     * pass, and about the symbol rather than about the tests.
     */
    public static final String BEHAVIOUR = "behaviour";

    private Attributes() { }
}

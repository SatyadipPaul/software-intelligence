package io.softwareintelligence.embedding;

import java.util.List;

/**
 * Turns text into a unit vector, so two pieces of text can be compared by how close they mean rather
 * than by which words they share.
 *
 * <p>This exists because of one measurement. Questions that name the symbol they are about are
 * answered 0.974 of the time by lexical retrieval; questions that describe it instead, 0.180. The
 * corpus calls that register mismatch: "switches a test off" and {@code Disabled} share no term, and
 * no amount of better structure around the identifiers changes that — four separate attempts to do
 * so moved nothing.
 *
 * <p>An encoder is always optional. Every caller must work, with lexical scoring, when none is
 * configured: the library is local-first and must not require a model to be useful.
 */
public interface TextEncoder extends AutoCloseable {

    /** How many components each vector has. */
    int dimensions();

    /**
     * Encodes a batch as L2-normalised vectors, one per input, in order.
     *
     * <p>Batched rather than one-at-a-time because indexing embeds thousands of cards at once and
     * per-call overhead dominates at that size. Normalised on the way out so a similarity is a dot
     * product and no caller has to remember to normalise.
     */
    float[][] encode(List<String> texts);

    /** Convenience for the single-text case, which is what a query is. */
    default float[] encodeOne(String text) {
        return encode(List.of(text))[0];
    }

    /** Cosine similarity of two vectors this encoder produced, which is their dot product. */
    static double similarity(float[] left, float[] right) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("vectors have different sizes: " + left.length + " and " + right.length);
        }
        double sum = 0.0;
        for (int i = 0; i < left.length; i++) sum += (double) left[i] * right[i];
        return sum;
    }

    @Override
    void close();
}

package io.softwareintelligence.queryengine;

/**
 * The two pieces of vector arithmetic both dense indexes need, so they cannot disagree.
 *
 * <p>What a node's vector means has to be the same whether it was built for flat retrieval or for a
 * tree card, because the same question is scored against both.
 */
final class Vectors {

    private Vectors() { }

    /**
     * Averages rows {@code [from, to)} as unit vectors: each source of prose counts once.
     *
     * <p>Concatenating the sources into one string instead would weigh each by how many words it
     * happens to have, and the added sources are the wordy ones — a name is three words and a commit
     * digest is forty, so mean-pooling one string lets a change log outvote the identifier. Measured
     * twice, on two different added sources, the same node failed the same way: {@code
     * AnnotatedClass} answered jd-064 at rank 1 and fell out of the top five once test names were
     * appended to it, and again once commit subjects were. Normalising per source caps how far any
     * one of them can pull.
     *
     * <p>A node with one source is returned untouched, which is every node until an optional prose
     * pass is switched on.
     */
    static float[] pool(float[][] rows, int from, int to) {
        if (to - from == 1) return rows[from];
        int dimensions = rows[from].length;
        float[] pooled = new float[dimensions];
        for (int row = from; row < to; row++) {
            float[] unit = rows[row].clone();
            normalize(unit);
            for (int d = 0; d < dimensions; d++) pooled[d] += unit[d];
        }
        normalize(pooled);
        return pooled;
    }

    /** Scales a vector to unit length, leaving a zero vector alone. */
    static void normalize(float[] vector) {
        double norm = 0;
        for (float component : vector) norm += (double) component * component;
        norm = Math.sqrt(norm);
        if (norm > 0) for (int d = 0; d < vector.length; d++) vector[d] /= (float) norm;
    }
}

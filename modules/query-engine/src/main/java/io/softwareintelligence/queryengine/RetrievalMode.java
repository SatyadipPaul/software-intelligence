package io.softwareintelligence.queryengine;

/**
 * Where a question's anchors come from.
 *
 * <p>{@code BM25} is the flat index that predates the tree, kept as the baseline every other mode
 * has to beat. {@code TREE} is descent alone, deliberately without the flat index's help, because a
 * tree that is only measured with a safety net underneath it has not been measured.
 * {@code HYBRID} is both: descent leads, and the flat hits behind it are the escape hatch for the
 * failure mode a tree has and a flat index does not — committing to a wrong branch at the first
 * level, which happens more in code than in prose because names repeat across modules.
 */
public enum RetrievalMode {
    BM25, TREE, HYBRID, DENSE, DENSE_HYBRID;

    public boolean needsTree() { return this == TREE || this == HYBRID; }

    public boolean needsFlat() { return this == BM25 || this == HYBRID || this == DENSE_HYBRID; }

    /** Whether this mode needs an encoder, and therefore a configured embedding model. */
    public boolean needsDense() { return this == DENSE || this == DENSE_HYBRID; }
}

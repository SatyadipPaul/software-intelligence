package io.softwareintelligence.session;

import io.softwareintelligence.indextree.IndexTree;
import io.softwareintelligence.indextree.IndexTreeBuilder;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.pipeline.RepositoryModel;
import io.softwareintelligence.queryengine.Bm25Index;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A graph and everything derived from it, held across many questions.
 *
 * <p>The command-line tool builds all of this per invocation and throws it away. On a repository
 * the size of jackson-databind that is about 28 seconds to analyze, 8 to derive the index tree and
 * 2 to build the retrieval index, against 63 milliseconds to actually answer — so a process that
 * answers one question spends better than 99% of its life getting ready to. Nothing in that list is
 * expensive work. It is the same work, repeated, because a process has nowhere to keep a result.
 * See {@code docs/cli-ergonomics-plan.md} for the measurements.
 *
 * <p>This is where it is kept. It exists on its own, with no interface attached, because both of
 * the things that want it — an interactive shell and a long-running server — want exactly it, and
 * building it inside whichever arrives first would bury it there.
 *
 * <h2>Staleness</h2>
 *
 * <p>A held graph describes the source as it was. {@link #refresh()} asks whether that is still
 * true and rebuilds when it is not, comparing both halves of the question: the bytes on disk, via
 * {@link SourceFingerprint}, and how the graph was asked for — classpath, layers, whether tests
 * were included — which changes the graph without changing a file.
 *
 * <p>When the answer cannot be established, it rebuilds. A fingerprint that cannot be taken is not
 * evidence that nothing moved, and the costs are not symmetric: rebuilding when it was unnecessary
 * costs seconds, while not rebuilding when it was necessary answers questions about code that is no
 * longer there, and says nothing to suggest the answer is stale.
 *
 * <h2>Threading</h2>
 *
 * <p>Not thread-safe. A server handling concurrent requests must confine an instance to one thread
 * or guard it; this class will not pretend to do that for it.
 */
public final class AnalysisSession {

    /** How the graph was asked for. Two graphs built from identical source still differ on this. */
    public record Request(Path repository, List<Path> classpath, boolean includeTests,
                          RepositoryModel.Layers layers) {

        public Request {
            Objects.requireNonNull(repository, "repository");
            classpath = List.copyOf(classpath);
            Objects.requireNonNull(layers, "layers");
        }

        /** Every layer, tests included, no classpath — what {@code RepositoryModel.build(Path)} does. */
        public static Request of(Path repository) {
            return new Request(repository, List.of(), true, RepositoryModel.Layers.all());
        }
    }

    private final Request request;
    private final RepositoryModel model = new RepositoryModel();

    private CodeGraph graph;
    private SourceFingerprint fingerprint;

    // Derived lazily and dropped together whenever the graph is replaced. Deriving the tree costs
    // seconds on a large repository, and a caller that only ever runs `impact` should never pay it.
    private IndexTree tree;
    private Bm25Index bm25;

    private AnalysisSession(Request request) {
        this.request = request;
    }

    /** Opens a session and builds its graph. */
    public static AnalysisSession open(Request request) throws IOException {
        AnalysisSession session = new AnalysisSession(Objects.requireNonNull(request, "request"));
        session.rebuild();
        return session;
    }

    /** Opens a session over a repository with every layer and no classpath. */
    public static AnalysisSession open(Path repository) throws IOException {
        return open(Request.of(repository));
    }

    public Request request() { return request; }

    public CodeGraph graph() { return graph; }

    /** The source this graph was built from, for a caller that wants to report or record it. */
    public SourceFingerprint fingerprint() { return fingerprint; }

    /** The navigable index tree, derived on first use and reused until the graph changes. */
    public IndexTree tree() {
        if (tree == null) tree = IndexTreeBuilder.derive(graph);
        return tree;
    }

    /** The BM25 retrieval index, built on first use and reused until the graph changes. */
    public Bm25Index bm25() {
        if (bm25 == null) bm25 = Bm25Index.over(graph);
        return bm25;
    }

    /**
     * Whether the source has moved since the graph was built.
     *
     * <p>Costs a read of every source file, which is a fraction of a second where rebuilding is
     * tens. When the question cannot be answered — an unreadable tree, a repository that has been
     * removed — the answer is yes, because the alternative is to keep serving a graph that may
     * describe nothing.
     */
    public boolean stale() {
        try {
            return !SourceFingerprint.of(request.repository(), request.includeTests()).equals(fingerprint);
        } catch (IOException unreadable) {
            return true;
        }
    }

    /**
     * Rebuilds if the source has moved, and reports whether it did.
     *
     * <p>Call this between questions rather than trusting a session to stay current: a file saved
     * in an editor between one question and the next is the ordinary case, not the exception.
     */
    public boolean refresh() throws IOException {
        if (!stale()) return false;
        rebuild();
        return true;
    }

    private void rebuild() throws IOException {
        // The fingerprint is taken first. Taken afterwards, an edit landing during analysis would
        // be recorded as the state the graph describes, and the session would never notice it.
        fingerprint = SourceFingerprint.of(request.repository(), request.includeTests());
        graph = model.build(request.repository(), request.classpath(), request.includeTests(), request.layers());
        tree = null;
        bm25 = null;
    }
}

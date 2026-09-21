package io.softwareintelligence.pipeline;

import io.softwareintelligence.analyzer.java.JavaRepositoryAnalyzer;
import io.softwareintelligence.analyzer.java.CommitVocabulary;
import io.softwareintelligence.analyzer.java.TestVocabulary;
import io.softwareintelligence.architecture.Capabilities;
import io.softwareintelligence.architecture.Communities;
import io.softwareintelligence.architecture.Modules;
import io.softwareintelligence.architecture.Workflows;
import io.softwareintelligence.framework.spring.SpringModelEnricher;
import io.softwareintelligence.model.CodeGraph;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Composes the layers in the one order the architecture requires: deterministic Java facts first,
 * then framework interpretation, then architecture summaries. Each layer only reads what the
 * previous one proved, so a graph can be built with any suffix of the pipeline switched off and the
 * facts underneath stay identical.
 */
public final class RepositoryModel {

    /**
     * Which layers to run. Deterministic Java analysis is not optional; everything above it is.
     *
     * <p>{@code testVocabulary} is off in both factories, including {@code all()}, and that is not
     * an oversight: it measured <em>negative</em> on every corpus available here, and a layer that
     * costs recall is not part of "all". It stays switchable because the case it was built for —
     * undocumented business code — is the case no repository in this corpus represents, so the
     * measurement rules it out for documented OSS and says nothing about the rest. See
     * {@code docs/benchmarks/test-vocabulary-2026-09-17.md}.
     *
     * <p>{@code commitVocabulary} is off for a different reason: it reads git history rather than
     * the source at the pinned commit, so it needs a repository that still has its history and it
     * refuses a shallow clone. Turning it on is a decision about what the graph is allowed to
     * contain, which is not a decision a default should make.
     */
    public record Layers(boolean framework, boolean architecture, int workflowDepth,
                         boolean testVocabulary, boolean commitVocabulary) {
        public static Layers all() { return new Layers(true, true, 8, false, false); }
        public static Layers deterministicOnly() { return new Layers(false, false, 0, false, false); }
    }

    private final JavaRepositoryAnalyzer analyzer = new JavaRepositoryAnalyzer();

    public CodeGraph build(Path repository, List<Path> classpath, boolean includeTests, Layers layers) throws IOException {
        CodeGraph graph = analyzer.analyze(repository, classpath, includeTests);
        // Before the layers above, because they read attributes and these write them, and after
        // analysis, because they need edges and provenance that only exist once every file is parsed.
        // Test vocabulary runs first so commit vocabulary can see what it already described and
        // leave it alone: two descriptions on one symbol dilute each other.
        if (layers.testVocabulary()) TestVocabulary.attach(graph);
        if (layers.commitVocabulary()) CommitVocabulary.attach(graph, repository);
        if (layers.framework()) new SpringModelEnricher().enrich(graph);
        if (layers.architecture()) {
            Modules.detect(graph);
            Workflows.discover(graph, layers.workflowDepth());
            Capabilities.detect(graph);
        }
        return graph;
    }

    public CodeGraph build(Path repository, List<Path> classpath) throws IOException {
        return build(repository, classpath, true, Layers.all());
    }

    /** Communities are computed on demand rather than recorded, because the strategy is a choice. */
    public List<Communities.Community> communities(CodeGraph graph, Communities.Strategy strategy, int k) {
        return Communities.detect(graph, strategy, k);
    }
}

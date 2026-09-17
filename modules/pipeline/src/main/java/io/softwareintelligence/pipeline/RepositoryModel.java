package io.softwareintelligence.pipeline;

import io.softwareintelligence.analyzer.java.JavaRepositoryAnalyzer;
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
     */
    public record Layers(boolean framework, boolean architecture, int workflowDepth, boolean testVocabulary) {
        public static Layers all() { return new Layers(true, true, 8, false); }
        public static Layers deterministicOnly() { return new Layers(false, false, 0, false); }
    }

    private final JavaRepositoryAnalyzer analyzer = new JavaRepositoryAnalyzer();

    public CodeGraph build(Path repository, List<Path> classpath, boolean includeTests, Layers layers) throws IOException {
        CodeGraph graph = analyzer.analyze(repository, classpath, includeTests);
        // Before the layers above, because they read attributes and this one writes them, and after
        // analysis, because it needs call edges that only resolve once every file has been parsed.
        if (layers.testVocabulary()) TestVocabulary.attach(graph);
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

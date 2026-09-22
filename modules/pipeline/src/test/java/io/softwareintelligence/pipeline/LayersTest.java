package io.softwareintelligence.pipeline;

import io.softwareintelligence.pipeline.RepositoryModel.Layers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layer selection a library caller writes.
 *
 * <p>These pin that each wither changes one thing. A five-field record whose fields are four
 * booleans is exactly the shape where a copy-paste slip swaps two of them and nothing fails: the
 * graph is simply built with the wrong layers, and every number measured from it is quietly off.
 */
final class LayersTest {

    @Test void all_runs_the_layers_that_pay_and_leaves_out_the_one_that_does_not() {
        Layers layers = Layers.all();
        assertTrue(layers.framework());
        assertTrue(layers.architecture());
        assertEquals(8, layers.workflowDepth());
        // Measured negative on every corpus available, so it is not part of "all".
        assertFalse(layers.testVocabulary());
        // Reads git history rather than the pinned commit, so it is never a default.
        assertFalse(layers.commitVocabulary());
    }

    @Test void deterministic_only_runs_nothing_above_java_analysis() {
        Layers layers = Layers.deterministicOnly();
        assertFalse(layers.framework());
        assertFalse(layers.architecture());
        assertEquals(0, layers.workflowDepth());
    }

    @Test void each_wither_changes_exactly_one_field() {
        Layers base = Layers.all();

        assertFalse(base.withFramework(false).framework());
        assertTrue(base.withFramework(false).architecture(), "architecture must be untouched");

        assertFalse(base.withArchitecture(false).architecture());
        assertTrue(base.withArchitecture(false).framework(), "framework must be untouched");

        assertTrue(base.withCommitVocabulary(true).commitVocabulary());
        assertFalse(base.withCommitVocabulary(true).testVocabulary(), "test vocabulary must be untouched");

        assertTrue(base.withTestVocabulary(true).testVocabulary());
        assertFalse(base.withTestVocabulary(true).commitVocabulary(), "commit vocabulary must be untouched");

        assertEquals(3, base.withWorkflowDepth(3).workflowDepth());
        assertEquals(8, base.workflowDepth(), "the original must be unchanged");
    }

    @Test void withers_compose_without_losing_earlier_choices() {
        Layers layers = Layers.all().withArchitecture(false).withCommitVocabulary(true).withWorkflowDepth(2);
        assertTrue(layers.framework());
        assertFalse(layers.architecture());
        assertTrue(layers.commitVocabulary());
        assertEquals(2, layers.workflowDepth());
    }

    @Test void a_negative_workflow_depth_is_rejected_where_it_is_written() {
        // Rather than silently discovering no workflows and reporting an empty result as a finding.
        assertThrows(IllegalArgumentException.class, () -> Layers.all().withWorkflowDepth(-1));
        assertThrows(IllegalArgumentException.class, () -> new Layers(true, true, -1, false, false));
    }
}

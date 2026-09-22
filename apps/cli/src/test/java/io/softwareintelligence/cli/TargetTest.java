package io.softwareintelligence.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that lets `impact PaymentService` and `impact . PaymentService` both work.
 *
 * <p>These are worth pinning because the failure mode is silent: a rule that reads one positional
 * as the wrong thing does not throw, it analyzes the wrong directory or searches for a symbol named
 * after a path, and the caller sees an empty result rather than an error.
 */
final class TargetTest {

    @Test void two_positionals_are_the_repository_and_the_subject() {
        Target target = Target.of("some/repo", "PaymentService", null, "symbol", new Main());
        assertEquals(Path.of("some/repo"), target.repository());
        assertEquals("PaymentService", target.subject());
    }

    @Test void one_positional_is_the_subject_and_the_repository_is_here() {
        Target target = Target.of("PaymentService", null, null, "symbol", new Main());
        assertEquals(Target.HERE, target.repository());
        assertEquals("PaymentService", target.subject());
    }

    @Test void the_repo_option_supplies_the_repository_when_no_positional_does() {
        Target target = Target.of("PaymentService", null, Path.of("elsewhere"), "symbol", new Main());
        assertEquals(Path.of("elsewhere"), target.repository());
        assertEquals("PaymentService", target.subject());
    }

    @Test void a_positional_repository_beats_the_repo_option() {
        // Otherwise a --repo left over in a shell alias would silently override what was typed.
        Target target = Target.of("some/repo", "PaymentService", Path.of("elsewhere"), "symbol", new Main());
        assertEquals(Path.of("some/repo"), target.repository());
    }

    @Test void a_missing_subject_names_itself_and_both_accepted_forms() {
        CommandLine.ParameterException thrown = assertThrows(CommandLine.ParameterException.class,
                () -> Target.of(null, null, null, "question", new Main()));
        assertTrue(thrown.getMessage().contains("question"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("<repository>"), thrown.getMessage());
    }

    @Test void a_subject_that_looks_like_a_path_is_still_a_subject() {
        // The rule is positional count, never a guess about the text or the filesystem: a symbol
        // may legitimately be named after something that also exists as a directory.
        Target target = Target.of("src", null, null, "symbol", new Main());
        assertEquals(Target.HERE, target.repository());
        assertEquals("src", target.subject());
    }

    @Test void a_lone_repository_positional_is_used_when_no_subject_is_wanted() {
        assertEquals(Path.of("some/repo"), Target.repository("some/repo", null));
    }

    @Test void an_omitted_repository_falls_back_to_the_option_then_to_here() {
        assertEquals(Path.of("elsewhere"), Target.repository(null, Path.of("elsewhere")));
        assertEquals(Target.HERE, Target.repository(null, null));
    }
}

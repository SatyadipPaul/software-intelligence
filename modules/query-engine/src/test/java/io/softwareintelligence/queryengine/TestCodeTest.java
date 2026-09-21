package io.softwareintelligence.queryengine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCodeTest {

    @Test void every_conventional_test_root_counts() {
        for (String root : new String[]{"src/test/java", "src/testFixtures/java", "src/it/java",
                "src/integration-test/java", "src/integrationTest/java"}) {
            assertTrue(TestCode.is("demo.Whatever", root + "/demo/Whatever.java"), root);
        }
    }

    @Test void a_nested_module_test_root_counts_too() {
        assertTrue(TestCode.is("demo.Thing", "modules/core/src/test/java/demo/Thing.java"));
    }

    @Test void production_code_in_a_package_called_test_is_not_test_code() {
        // The analyzer has a regression test for exactly this shape; the two must agree.
        assertFalse(TestCode.is("demo.test.Runner", "src/main/java/demo/test/Runner.java"));
    }

    @Test void the_name_is_a_fallback_for_sources_with_no_conventional_root() {
        assertTrue(TestCode.is("demo.PaymentServiceTest", "app/demo/PaymentServiceTest.java"));
        assertTrue(TestCode.is("demo.PaymentTests", "app/demo/PaymentTests.java"));
        assertFalse(TestCode.is("demo.PaymentService", "app/demo/PaymentService.java"));
    }

    @Test void demotion_is_monotone_for_both_signs() {
        // Multiplying a signed score is not safe on its own: -0.4 * 0.1 is -0.04, which ranks
        // HIGHER than -0.4. Demotion must never move a node up.
        assertTrue(TestCode.demote(0.8) < 0.8);
        assertTrue(TestCode.demote(-0.4) <= -0.4);
        assertEquals(0.0, TestCode.demote(0.0));
    }

    @Test void a_demoted_score_still_beats_a_much_worse_one() {
        // Demoted, not removed: "what covers this?" has a test class for an answer.
        assertTrue(TestCode.demote(0.9) > 0.01);
    }
}

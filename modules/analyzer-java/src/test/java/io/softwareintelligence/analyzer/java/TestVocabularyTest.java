package io.softwareintelligence.analyzer.java;

import io.softwareintelligence.model.Attributes;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.Provenance;
import io.softwareintelligence.model.RelationKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins which subject a test name is filed under, and what survives the budget.
 *
 * <p>These are the rules the benchmark cannot check. A benchmark says whether the attribute helps;
 * it cannot say whether a name landed on the right type, whether a fixture was excluded, or whether
 * a digest stayed inside its cap — and those are the three ways this pass can be wrong while the
 * number still looks plausible.
 */
class TestVocabularyTest {

    private static final Provenance MAIN = new Provenance("JDT_AST", 1.0, "src/main/java/demo/Demo.java", 5, 1);
    private static final Provenance TEST = new Provenance("JDT_AST", 1.0, "src/test/java/demo/DemoTest.java", 5, 1);

    private static void type(CodeGraph graph, String id, Provenance where, Map<String, String> attributes) {
        graph.upsertNode(new GraphNode(id, EntityKind.TYPE, id.substring("type:".length()),
                attributes, where), true);
    }

    private static void method(CodeGraph graph, String owner, String name, Provenance where, String annotations) {
        String id = owner + "#" + name + "()";
        graph.upsertNode(new GraphNode(id, EntityKind.METHOD, name,
                Map.of("annotations", annotations), where), true);
        graph.addEdge(new GraphEdge(owner, id, RelationKind.DECLARES, Map.of(), where));
    }

    private static void calls(CodeGraph graph, String from, String to) {
        graph.addEdge(new GraphEdge(from, to, RelationKind.CALLS, Map.of(), TEST));
    }

    private static String behaviour(CodeGraph graph, String id) {
        return graph.node(id).map(node -> node.attributes().getOrDefault(Attributes.BEHAVIOUR, "")).orElse("");
    }

    /**
     * Two undocumented production types with a test class each.
     *
     * <p>Two subjects rather than one on purpose: with a single subject every test in the suite
     * exercises it, and the fixture rule would — correctly — refuse to describe it.
     */
    private static CodeGraph graph() {
        CodeGraph graph = new CodeGraph();
        type(graph, "type:demo.PetValidator", MAIN, Map.of());
        method(graph, "type:demo.PetValidator", "validate", MAIN, "");
        type(graph, "type:demo.PetValidatorTest", TEST, Map.of());
        method(graph, "type:demo.PetValidatorTest", "shouldRejectAPetWithNoName", TEST, "Test");
        method(graph, "type:demo.PetValidatorTest", "shouldRejectAPetBornInTheFuture", TEST, "Test");
        method(graph, "type:demo.PetValidatorTest", "setUp", TEST, "BeforeEach");
        calls(graph, "type:demo.PetValidatorTest#shouldRejectAPetWithNoName()", "type:demo.PetValidator#validate()");
        calls(graph, "type:demo.PetValidatorTest#shouldRejectAPetBornInTheFuture()", "type:demo.PetValidator#validate()");
        calls(graph, "type:demo.PetValidatorTest#setUp()", "type:demo.PetValidator#validate()");

        type(graph, "type:demo.OwnerRepository", MAIN, Map.of());
        method(graph, "type:demo.OwnerRepository", "findByLastName", MAIN, "");
        type(graph, "type:demo.OwnerRepositoryTest", TEST, Map.of());
        for (String name : List.of("shouldFindOwnersByLastName", "shouldIgnoreSurroundingWhitespace",
                "shouldReturnEveryOwnerForAnEmptyQuery")) {
            method(graph, "type:demo.OwnerRepositoryTest", name, TEST, "Test");
            calls(graph, "type:demo.OwnerRepositoryTest#" + name + "()", "type:demo.OwnerRepository#findByLastName()");
        }
        return graph;
    }

    @Test void a_test_name_describes_the_type_it_exercises() {
        CodeGraph graph = graph();
        TestVocabulary.attach(graph);

        String behaviour = behaviour(graph, "type:demo.PetValidator");
        assertTrue(behaviour.contains("reject pet with no name"), behaviour);
        assertTrue(behaviour.contains("born in the future"), behaviour);
    }

    @Test void the_test_itself_is_never_described() {
        CodeGraph graph = graph();
        TestVocabulary.attach(graph);

        // The signal is about the subject. A test that described itself would be ranked by it, and
        // retrieval spends effort demoting tests rather than promoting them.
        assertEquals("", behaviour(graph, "type:demo.PetValidatorTest"));
    }

    @Test void setup_methods_do_not_contribute_their_mechanics() {
        CodeGraph graph = graph();
        TestVocabulary.attach(graph);

        assertFalse(behaviour(graph, "type:demo.PetValidator").contains("set up"),
                behaviour(graph, "type:demo.PetValidator"));
    }

    @Test void a_documented_type_keeps_its_own_sentence_and_gets_nothing_added() {
        CodeGraph graph = new CodeGraph();
        type(graph, "type:demo.PetValidator", MAIN, Map.of(Attributes.DOC, "Validates a pet before it is saved."));
        method(graph, "type:demo.PetValidator", "validate", MAIN, "");
        type(graph, "type:demo.PetValidatorTest", TEST, Map.of());
        method(graph, "type:demo.PetValidatorTest", "shouldRejectAPetWithNoName", TEST, "Test");
        calls(graph, "type:demo.PetValidatorTest#shouldRejectAPetWithNoName()", "type:demo.PetValidator#validate()");

        TestVocabulary.attach(graph);

        assertEquals("", behaviour(graph, "type:demo.PetValidator"));
        assertEquals("Validates a pet before it is saved.",
                graph.node("type:demo.PetValidator").orElseThrow().attributes().get(Attributes.DOC));
    }

    @Test void a_type_most_of_the_suite_touches_is_treated_as_a_fixture() {
        CodeGraph graph = new CodeGraph();
        type(graph, "type:demo.Mapper", MAIN, Map.of());
        method(graph, "type:demo.Mapper", "map", MAIN, "");
        type(graph, "type:demo.Tests", TEST, Map.of());
        for (int i = 0; i < 4; i++) {
            method(graph, "type:demo.Tests", "shouldDoThing" + (char) ('A' + i), TEST, "Test");
            calls(graph, "type:demo.Tests#shouldDoThing" + (char) ('A' + i) + "()", "type:demo.Mapper#map()");
        }

        TestVocabulary.attach(graph);

        // Every test in the suite calls it, so nothing it is called by distinguishes it.
        assertEquals("", behaviour(graph, "type:demo.Mapper"));
    }

    @Test void a_digest_stays_within_its_budget_however_many_tests_there_are() {
        Set<String> names = new TreeSet<>();
        for (int i = 0; i < 500; i++) names.add("shouldHandleScenarioNumber" + i + "Correctly" + (char) ('a' + i % 26));

        String digest = TestVocabulary.digest(names);

        assertTrue(digest.length() <= 240, "digest was " + digest.length() + " characters");
    }

    @Test void a_phrase_drops_the_word_that_only_marks_it_a_test() {
        assertEquals("reject payment when balance is insufficient",
                TestVocabulary.phrase("shouldRejectPaymentWhenBalanceIsInsufficient"));
        assertEquals("parses an empty document", TestVocabulary.phrase("testParsesAnEmptyDocument"));
        // One-letter words are dropped with the rest of the noise: "a" is not vocabulary.
        assertEquals("reject pet with no name", TestVocabulary.phrase("shouldRejectAPetWithNoName"));
    }

    @Test void an_issue_number_in_a_test_name_is_not_vocabulary() {
        // The rarest token in the repository and the least meaningful, which is the worst thing to
        // hand to anything that weighs a term by how rare it is.
        assertEquals("fails to parse", TestVocabulary.phrase("testFailsToParse2049"));
        assertEquals("coercion fail", TestVocabulary.phrase("testCoercionFail3690"));
    }

    @Test void a_phrase_that_says_nothing_new_is_dropped_rather_than_repeated() {
        Set<String> names = new TreeSet<>(List.of(
                "shouldAddAllWithNull", "shouldAddAllWithNullAgain", "shouldRejectAMalformedCursor"));

        String digest = TestVocabulary.digest(names);

        // Widest first, and "add all with null" is then entirely contained in what was already
        // said. A card that lists it anyway spends its budget saying the same thing twice.
        assertEquals("add all with null again; reject malformed cursor", digest);
    }

    @Test void a_graph_with_no_tests_is_left_exactly_as_it_was() {
        CodeGraph graph = new CodeGraph();
        type(graph, "type:demo.PetValidator", MAIN, Map.of());
        method(graph, "type:demo.PetValidator", "validate", MAIN, "");

        TestVocabulary.attach(graph);

        assertEquals("", behaviour(graph, "type:demo.PetValidator"));
    }
}

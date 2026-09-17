package io.softwareintelligence.analyzer.java;

import io.softwareintelligence.model.Attributes;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.RelationKind;
import io.softwareintelligence.model.TestSources;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reads test method names as prose about the production code they exercise.
 *
 * <p>This is the cheapest Tier 0 source and the one most likely to survive on undocumented code:
 * business code is often uncommented but almost always tested, and a Java test name is a sentence
 * with the spaces removed — {@code shouldRejectPaymentWhenBalanceIsInsufficient} carries exactly
 * the vocabulary a question-asker uses and that the production identifier {@code authorize}
 * compresses away. The graph already held those names; nothing associated them with the subject.
 *
 * <p>The attribute lands on the <em>production type</em>, never on the test's own node. The signal
 * is about the subject. A test keeps describing itself, which is what the retrieval demotion is
 * for.
 *
 * <p>Deterministic, offline, and derived only from edges the analyzer already proved. It reads
 * {@code CALLS}, so it is silent on a repository analyzed without a classpath, where those edges do
 * not resolve.
 *
 * <p><b>This pass is off by default, because it measured negative.</b> On the four repositories
 * available here it cost subject-free retrieval 0.522 → 0.509 even in its least invasive form, and
 * 0.522 → 0.484 when allowed to sit beside an existing doc sentence. It is kept and kept runnable
 * because every repository in that corpus is documented open-source code, which is precisely not
 * the case the idea was for: on business code with no Javadoc, the digest has nothing to compete
 * with. That case is untested here, so the measurement rules the pass out for documented code and
 * says nothing about the rest. See {@code docs/benchmarks/test-vocabulary-2026-09-17.md}.
 */
public final class TestVocabulary {

    /**
     * How many subjects one test may speak for.
     *
     * <p>A test calls whatever it needs — a mapper, a builder, three assertions — and only some of
     * that is its subject. Keeping the least-exercised targets is the cheap form of the right idea:
     * the type a test touches that almost nothing else touches is the type that test is about.
     */
    static final int SUBJECTS_PER_TEST = 3;

    /**
     * Above this share of the suite, a type is the suite's fixture rather than its subject.
     *
     * <p>jackson-databind's {@code ObjectMapper} is called by more than half of its tests. Three
     * thousand unrelated behaviours squeezed into a 240-character digest is not a description of
     * that type, it is a description of the library, and it would match questions about everything.
     * A type nothing distinguishes gets no description rather than an arbitrary one.
     *
     * <p>Half, rather than a tighter share, because the rule has to name something a reader can
     * check: a type the majority of a test suite touches is what the suite is built on. Tightening
     * it to a fifth also swallowed spring-petclinic's {@code Owner} and {@code OwnerController},
     * which are exactly what their tests are about.
     */
    static final double FIXTURE_SHARE = 0.5;

    /** Names that start a test and say nothing: the phrase begins after them. */
    private static final Set<String> LEADING_NOISE = Set.of(
            "test", "tests", "should", "shall", "must", "can", "will", "does", "do", "is", "it",
            "verify", "verifies", "check", "checks", "ensure", "ensures", "assert", "asserts");

    /** Annotations that mark a method as a test in the JUnit 4 and 5 families, and TestNG. */
    private static final Set<String> TEST_ANNOTATIONS = Set.of(
            "Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate");

    private TestVocabulary() { }

    /**
     * Writes a bounded digest of test names onto each production type those tests exercise.
     *
     * <p>A no-op when the graph holds no test sources, which is what {@code --no-tests} produces.
     */
    public static void attach(CodeGraph graph) {
        Map<String, GraphNode> byId = new HashMap<>();
        for (GraphNode node : graph.nodes()) byId.put(node.id(), node);

        Map<String, Set<String>> subjectsOf = new TreeMap<>();
        for (GraphNode node : graph.nodes()) {
            if (!isTestMethod(node)) continue;
            Set<String> subjects = new TreeSet<>();
            for (GraphEdge edge : graph.outgoing(node.id())) {
                if (edge.kind() != RelationKind.CALLS) continue;
                GraphNode called = byId.get(edge.to());
                if (called == null || called.kind() != EntityKind.METHOD) continue;
                GraphNode owner = byId.get(declaringType(edge.to()));
                // External symbols are skipped rather than described: a test name says something
                // about the repository's own code, and nothing about java.lang.String.
                if (owner == null || owner.kind() == EntityKind.EXTERNAL_SYMBOL || isTest(owner)) continue;
                subjects.add(owner.id());
            }
            if (!subjects.isEmpty()) subjectsOf.put(node.id(), subjects);
        }
        if (subjectsOf.isEmpty()) return;

        Map<String, Integer> exercisedBy = new HashMap<>();
        for (Set<String> subjects : subjectsOf.values()) {
            for (String subject : subjects) exercisedBy.merge(subject, 1, Integer::sum);
        }
        int fixture = (int) Math.ceil(subjectsOf.size() * FIXTURE_SHARE);

        Map<String, Set<String>> namesOf = new TreeMap<>();
        for (Map.Entry<String, Set<String>> entry : subjectsOf.entrySet()) {
            String name = byId.get(entry.getKey()).name();
            List<String> ranked = new ArrayList<>(entry.getValue());
            ranked.sort(Comparator.comparingInt((String id) -> exercisedBy.getOrDefault(id, 0))
                    .thenComparing(Comparator.naturalOrder()));
            for (String subject : ranked.subList(0, Math.min(SUBJECTS_PER_TEST, ranked.size()))) {
                if (exercisedBy.getOrDefault(subject, 0) > fixture) continue;
                namesOf.computeIfAbsent(subject, ignored -> new TreeSet<>()).add(name);
            }
        }

        for (Map.Entry<String, Set<String>> entry : namesOf.entrySet()) {
            GraphNode subject = byId.get(entry.getKey());
            // Only where there is nothing better. Measured both ways: adding the digest next to a
            // doc sentence cost 0.522 -> 0.484 on subject-free retrieval, filling the gap where no
            // doc sentence exists cost 0.522 -> 0.509, and the whole of the second loss was on one
            // repository. A test name is weaker prose than a maintainer's sentence, and putting the
            // two in one document dilutes the better one.
            if (!subject.attributes().getOrDefault(Attributes.DOC, "").isBlank()) continue;
            String digest = digest(entry.getValue());
            if (digest.isEmpty()) continue;
            Map<String, String> attributes = new java.util.LinkedHashMap<>(subject.attributes());
            attributes.put(Attributes.BEHAVIOUR, digest);
            graph.upsertNode(new GraphNode(subject.id(), subject.kind(), subject.name(),
                    Map.copyOf(attributes), subject.provenance()), true);
        }
    }

    /** The selected names as one bounded, deduplicated line. */
    static String digest(Set<String> names) {
        Set<String> phrases = new TreeSet<>();
        for (String name : names) {
            String phrase = phrase(name);
            if (!phrase.isEmpty()) phrases.add(phrase);
        }
        return VocabularyDigest.of(phrases);
    }

    /** A test name as the sentence it was written to be, minus the word that only marks it a test. */
    static String phrase(String name) {
        String spaced = name.replaceAll("[^A-Za-z0-9]+", " ")
                .replaceAll("(?<!^)(?=[A-Z][a-z])|(?<=[a-z0-9])(?=[A-Z])", " ");
        List<String> words = new ArrayList<>();
        for (String word : spaced.split(" ")) {
            // Digits in a test name are an issue number - `failsToParse2049`, `coercionFail3690`.
            // They are the rarest tokens in the repository and the least meaningful to anyone
            // asking a question, which is the worst combination for anything that weighs a term by
            // how rare it is. Stripped rather than dropped, because the word they are stuck to is
            // usually the informative one: `parse2049` is noise, `parse` is not.
            String letters = word.replaceAll("[0-9]+", "");
            if (letters.length() > 1) words.add(letters.toLowerCase(Locale.ROOT));
        }
        int start = 0;
        while (start < words.size() && LEADING_NOISE.contains(words.get(start))) start++;
        return String.join(" ", words.subList(start, words.size()));
    }

    /**
     * A test method, rather than any method a test file happens to declare.
     *
     * <p>Setup, teardown and helper methods are named for their mechanics, not for behaviour, and
     * including them would dilute the digest with {@code set up} and {@code create mapper}. The
     * annotation is the modern answer and the {@code test} prefix is the JUnit 3 one; a repository
     * on either gets the same treatment.
     */
    private static boolean isTestMethod(GraphNode node) {
        if (node.kind() != EntityKind.METHOD) return false;
        if (!TestSources.inTestTree(node.provenance().file())) return false;
        for (String annotation : node.attributes().getOrDefault("annotations", "").split(",")) {
            if (TEST_ANNOTATIONS.contains(annotation.trim())) return true;
        }
        return node.name().toLowerCase(Locale.ROOT).startsWith("test");
    }

    private static boolean isTest(GraphNode node) {
        return TestSources.is(node.name(), node.provenance().file());
    }

    /** The type id a method id belongs to: {@code type:a.B#c()} is declared by {@code type:a.B}. */
    private static String declaringType(String methodId) {
        int hash = methodId.indexOf('#');
        return hash < 0 ? methodId : methodId.substring(0, hash);
    }
}

package io.softwareintelligence.queryengine;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BM25 over the graph's own symbol vocabulary — identifiers, package segments, annotations, HTTP
 * paths — rather than over raw file text. Retrieval therefore returns symbols that can be traversed
 * and cited, not line ranges that still have to be located in the graph.
 *
 * <p>Identifiers are split on camel case and on the separators the id scheme uses, so a query for
 * "payment authorization" reaches {@code PaymentService#authorize}. The index is local, in memory,
 * and needs no service.
 */
public final class Bm25Index {
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    public record Hit(GraphNode node, double score) { }

    private final List<GraphNode> documents = new ArrayList<>();
    private final List<Map<String, Integer>> terms = new ArrayList<>();
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private double averageLength;

    public static Bm25Index over(CodeGraph graph) {
        Bm25Index index = new Bm25Index();
        for (GraphNode node : graph.nodes()) {
            if (node.kind() == EntityKind.EXTERNAL_SYMBOL) continue;
            index.add(node);
        }
        index.finish();
        return index;
    }

    private void add(GraphNode node) {
        Map<String, Integer> counts = new HashMap<>();
        for (String term : tokenize(text(node))) counts.merge(term, 1, Integer::sum);
        documents.add(node);
        terms.add(counts);
        counts.keySet().forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
    }

    private void finish() {
        averageLength = terms.stream().mapToInt(counts -> counts.values().stream().mapToInt(Integer::intValue).sum()).average().orElse(1.0);
    }

    /**
     * English words that carry no signal but collide with real identifiers. Removed from queries
     * only, never from the index: a symbol genuinely called {@code on} should still be findable by
     * its id, and removing index terms would change what "the document contains" means.
     */
    private static final java.util.Set<String> QUESTION_WORDS = java.util.Set.of(
            "what", "which", "who", "whom", "whose", "where", "when", "why", "how", "does", "do", "did",
            "is", "are", "was", "were", "be", "been", "am", "the", "a", "an", "of", "on", "in", "to",
            "for", "from", "by", "with", "and", "or", "not", "this", "that", "these", "those", "it",
            "its", "as", "at", "any", "all", "can", "could", "should", "would", "will", "there", "here",
            "me", "my", "our", "us", "you", "your", "if", "then", "than", "about", "into", "over", "use",
            "used", "uses", "using", "get", "gets", "have", "has", "had", "list", "show", "tell");

    /**
     * The terms a question actually asks about. Shared with tree navigation so a question is
     * reduced the same way whichever retrieval path reads it.
     */
    static List<String> queryTerms(String query) {
        List<String> terms = tokenize(query).stream().filter(term -> !QUESTION_WORDS.contains(term)).toList();
        return terms.isEmpty() ? tokenize(query) : terms;
    }

    public List<Hit> search(String query, int limit) {
        List<String> queryTerms = queryTerms(query);
        if (queryTerms.isEmpty()) return List.of();
        int count = documents.size();
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Integer> counts = terms.get(i);
            int length = counts.values().stream().mapToInt(Integer::intValue).sum();
            double score = 0.0;
            for (String term : queryTerms) {
                Integer frequency = counts.get(term);
                if (frequency == null) continue;
                int containing = documentFrequency.getOrDefault(term, 0);
                double idf = Math.log(1 + (count - containing + 0.5) / (containing + 0.5));
                score += idf * (frequency * (K1 + 1)) / (frequency + K1 * (1 - B + B * length / averageLength));
            }
            if (score > 0) hits.add(new Hit(documents.get(i), score * prior(documents.get(i))));
        }
        // Ties break on id so the same query always returns the same ordering.
        return hits.stream()
                .sorted(Comparator.comparingDouble(Hit::score).reversed().thenComparing(hit -> hit.node().id()))
                .limit(limit).toList();
    }

    /**
     * A weight by what kind of thing a symbol is. Retrieval answers "which symbol does this question
     * concern", and a type is more often the answer than one of the hundreds of test fields that
     * merely mention its name. Applied after scoring so it reorders rather than filters.
     */
    private static double prior(GraphNode node) {
        return switch (node.kind()) {
            case CONTROLLER, SERVICE, REPOSITORY_COMPONENT, ENTITY, CONFIGURATION,
                 ENDPOINT, TOPIC, DATABASE_TABLE, BUSINESS_CAPABILITY, WORKFLOW,
                 SECURITY_GUARD, EXTERNAL_SERVICE, CONFIGURATION_PROPERTY, MODULE -> 1.30;
            case TYPE, INTERFACE -> 1.15;
            case METHOD -> 1.00;
            case FIELD -> 0.60;
            case FILE, PACKAGE, REPOSITORY -> 0.50;
            default -> 0.80;
        };
    }

    private static String text(GraphNode node) {
        StringBuilder text = new StringBuilder(node.name()).append(' ').append(node.id()).append(' ').append(node.kind());
        node.attributes().forEach((key, value) -> text.append(' ').append(key).append(' ').append(value));
        return text.toString();
    }

    /** Splits ids, qualified names, and camel-case identifiers into searchable terms. */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        for (String part : text.split("[^A-Za-z0-9]+")) {
            if (part.isBlank()) continue;
            String lower = part.toLowerCase(Locale.ROOT);
            if (lower.length() > 1) tokens.add(lower);
            StringBuilder word = new StringBuilder();
            for (int i = 0; i < part.length(); i++) {
                char character = part.charAt(i);
                boolean boundary = Character.isUpperCase(character) && i > 0 && !Character.isUpperCase(part.charAt(i - 1));
                if (boundary && word.length() > 1) {
                    tokens.add(word.toString().toLowerCase(Locale.ROOT));
                    word.setLength(0);
                }
                word.append(character);
            }
            if (word.length() > 1) tokens.add(word.toString().toLowerCase(Locale.ROOT));
        }
        return tokens;
    }
}

package io.softwareintelligence.queryengine;

import io.softwareintelligence.embedding.TextEncoder;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Retrieval by meaning rather than by shared words.
 *
 * <p>Built for one measured failure. On the 200-question corpus, questions that name the symbol they
 * are about are answered 0.974 of the time and questions that describe it 0.180 — the gap prior work
 * calls register mismatch. "Switches a test off" and {@code Disabled} share no term, so no term
 * index can connect them however good the card text gets, and four attempts at better structure
 * around the identifiers moved nothing.
 *
 * <p>Only nodes worth anchoring on are embedded — types and the containers above them, about an
 * eighth of the graph. That is not a saving bolted on afterwards: an anchor is a place to start
 * reading, and a method is reached through the type that declares it, so vectors for the other
 * seven eighths would cost index time to rank candidates no answer needs.
 */
public final class DenseIndex {

    /**
     * What is worth a vector. Members are deliberately absent: they are reached through their
     * declaring type, and on junit5 there are 408 methods called {@code test()} whose cards say
     * almost nothing an encoder can separate.
     */
    private static final Set<EntityKind> ANCHORABLE = EnumSet.of(
            EntityKind.TYPE, EntityKind.INTERFACE, EntityKind.CONTROLLER, EntityKind.SERVICE,
            EntityKind.REPOSITORY_COMPONENT, EntityKind.ENTITY, EntityKind.CONFIGURATION,
            EntityKind.MODULE, EntityKind.BUSINESS_CAPABILITY, EntityKind.WORKFLOW);

    public record Hit(GraphNode node, double similarity) { }

    private final List<GraphNode> documents;
    private final float[][] vectors;
    private final TextEncoder encoder;

    private DenseIndex(List<GraphNode> documents, float[][] vectors, TextEncoder encoder) {
        this.documents = documents;
        this.vectors = vectors;
        this.encoder = encoder;
    }

    /** Embeds every anchorable node once. Linear in the graph, and the cost of the whole mode. */
    public static DenseIndex over(CodeGraph graph, TextEncoder encoder) {
        List<GraphNode> documents = graph.nodes().stream()
                .filter(node -> ANCHORABLE.contains(node.kind()))
                .sorted(Comparator.comparing(GraphNode::id))
                .toList();
        List<String> texts = documents.stream().map(DenseIndex::text).toList();
        return new DenseIndex(documents, encoder.encode(texts), encoder);
    }

    public int size() { return documents.size(); }

    /**
     * The text a node is embedded as: its name split into words, then its documentation.
     *
     * <p>Split on camel case because an encoder is trained on prose — {@code BeforeEachCallback} is
     * one unknown token, "Before Each Callback" is three known ones, and only the second can be
     * close to "runs before every test". The doc sentence follows because it is the one part of a
     * Java repository already written in a reader's register.
     */
    private static String text(GraphNode node) {
        String simple = node.name();
        int dot = simple.lastIndexOf('.');
        if (dot >= 0 && dot < simple.length() - 1) simple = simple.substring(dot + 1);
        String words = simple.replaceAll("(?<!^)(?=[A-Z][a-z])|(?<=[a-z0-9])(?=[A-Z])", " ");
        String documentation = node.attributes().getOrDefault("doc", "");
        String summary = node.attributes().getOrDefault("claim.summary", "");
        StringBuilder text = new StringBuilder(words);
        if (!documentation.isBlank()) text.append(". ").append(documentation);
        else if (!summary.isBlank()) text.append(". ").append(summary);
        return text.toString();
    }

    /** The closest nodes to the question, most similar first. */
    public List<Hit> search(String question, int limit) {
        if (documents.isEmpty() || limit <= 0) return List.of();
        float[] query = encoder.encodeOne(question.toLowerCase(Locale.ROOT));
        List<Hit> hits = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            hits.add(new Hit(documents.get(i), TextEncoder.similarity(query, vectors[i])));
        }
        return hits.stream()
                .sorted(Comparator.comparingDouble(Hit::similarity).reversed()
                        .thenComparing(hit -> hit.node().id()))
                .limit(limit)
                .toList();
    }

    /** The vector for one node, for callers that aggregate rather than rank. */
    public Map.Entry<GraphNode, float[]> vectorAt(int position) {
        return Map.entry(documents.get(position), vectors[position]);
    }
}

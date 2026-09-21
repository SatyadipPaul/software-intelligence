package io.softwareintelligence.queryengine;

import io.softwareintelligence.embedding.TextEncoder;
import io.softwareintelligence.indextree.IndexCards;
import io.softwareintelligence.indextree.IndexNode;

import java.util.Comparator;
import java.util.List;

/**
 * Chooses branches by what they mean rather than by the words they share with the question.
 *
 * <p>The deterministic chooser reads a card with BM25, which is the one thing a descent cannot
 * afford at the top of a tree: the question says "switches a test off" and every module card says
 * neither word, so the first choice — the one that decides everything below it — is made almost at
 * random. This scores the same cards with the encoder instead.
 *
 * <p>It exists to answer a question about the architecture rather than to be the default. A descent
 * is only worth its round trips if a better chooser makes it better; measuring that needs a chooser
 * that is better, and this is the strongest automatable one available.
 */
public final class DenseChooser implements TreeNavigator.Chooser {

    private final TextEncoder encoder;
    private final DenseTreeIndex index;
    private final String question;
    private float[] embedded;

    public DenseChooser(TextEncoder encoder, DenseTreeIndex index, String question) {
        this.encoder = encoder;
        this.index = index;
        this.question = question;
    }

    @Override
    public List<String> choose(IndexNode parent, List<TreeNavigator.Scored> ranked, int keep) {
        if (ranked.isEmpty()) return List.of();
        if (embedded == null) embedded = encoder.encodeOne(question);
        record Choice(String id, double similarity) { }
        List<Choice> scored = new java.util.ArrayList<>(ranked.size());
        for (TreeNavigator.Scored candidate : ranked) {
            scored.add(new Choice(candidate.node().id(), index.score(embedded, candidate.node())));
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(Choice::similarity).reversed().thenComparing(Choice::id))
                .limit(Math.max(1, keep))
                .map(Choice::id)
                .toList();
    }
}

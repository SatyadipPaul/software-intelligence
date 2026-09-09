package io.softwareintelligence.queryengine;

import io.softwareintelligence.indextree.IndexCards;
import io.softwareintelligence.indextree.IndexNode;
import io.softwareintelligence.indextree.IndexTree;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25 over the tree's cards rather than over the graph's symbols.
 *
 * <p>The corpus is the whole tree, so a term is rare or common relative to the table of contents a
 * navigator is actually reading. Scoring a child against a corpus of individual symbols would make
 * every branch node look alike, because a branch's card is mostly the names of what it contains.
 */
final class CardIndex {
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final Map<String, Map<String, Integer>> terms = new HashMap<>();
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private int documents;
    private double averageLength;

    static CardIndex over(IndexTree tree) {
        CardIndex index = new CardIndex();
        long length = 0;
        for (IndexNode node : tree.nodes()) {
            Map<String, Integer> counts = new HashMap<>();
            for (String term : Bm25Index.tokenize(IndexCards.searchText(node))) counts.merge(term, 1, Integer::sum);
            index.terms.put(node.id(), counts);
            counts.keySet().forEach(term -> index.documentFrequency.merge(term, 1, Integer::sum));
            length += counts.values().stream().mapToInt(Integer::intValue).sum();
        }
        index.documents = tree.size();
        index.averageLength = index.documents == 0 ? 1.0 : Math.max(1.0, (double) length / index.documents);
        return index;
    }

    double score(List<String> queryTerms, IndexNode node) {
        Map<String, Integer> counts = terms.get(node.id());
        if (counts == null || counts.isEmpty()) return 0.0;
        int length = counts.values().stream().mapToInt(Integer::intValue).sum();
        double score = 0.0;
        for (String term : queryTerms) {
            Integer frequency = counts.get(term);
            if (frequency == null) continue;
            int containing = documentFrequency.getOrDefault(term, 0);
            double idf = Math.log(1 + (documents - containing + 0.5) / (containing + 0.5));
            score += idf * (frequency * (K1 + 1)) / (frequency + K1 * (1 - B + B * length / averageLength));
        }
        return score;
    }
}

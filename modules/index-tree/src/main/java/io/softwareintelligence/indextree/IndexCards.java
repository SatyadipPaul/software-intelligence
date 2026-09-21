package io.softwareintelligence.indextree;

import java.util.List;
import java.util.Map;

/**
 * Renders a tree node as the card a navigator sees: what this entry is, what it covers, and the
 * exact ids of the choices below it.
 *
 * <p>One card is the whole of what a navigator is shown at a step. That is the property that makes
 * an assistant-driven descent safe to run: an assistant that cannot see the rest of the tree cannot
 * choose a branch that was never in front of it, and the ids it may answer with are printed on the
 * card it was given.
 */
public final class IndexCards {
    private IndexCards() { }

    /** The text a scorer matches a question against. */
    public static String searchText(IndexNode node) {
        StringBuilder text = new StringBuilder(node.name()).append(' ').append(node.id()).append(' ').append(node.kind());
        for (String graphId : node.graphIds()) text.append(' ').append(graphId);
        for (Map.Entry<String, String> fact : node.facts().entrySet()) {
            // Counts are noise in a term index: every card has them, and "types 4" makes a card
            // match the word "types" in a question about something else entirely.
            if (isNumeric(fact.getValue())) continue;
            text.append(' ').append(fact.getKey()).append(' ').append(fact.getValue());
        }
        return text.toString();
    }

    /** The card itself, as shown to a reader or written into a navigation packet. */
    public static String render(IndexTree tree, IndexNode node) {
        StringBuilder card = new StringBuilder();
        card.append(node.id()).append("  [").append(node.kind()).append("]  ").append(node.name()).append('\n');
        if (!node.graphIds().isEmpty()) card.append("  covers: ").append(String.join(", ", node.graphIds())).append('\n');
        for (Map.Entry<String, String> fact : node.facts().entrySet()) {
            card.append("  ").append(fact.getKey()).append(": ").append(fact.getValue()).append('\n');
        }
        List<IndexNode> children = tree.children(node);
        if (children.isEmpty()) {
            card.append("  children: none (leaf)\n");
            return card.toString();
        }
        card.append("  children (").append(children.size()).append("):\n");
        for (IndexNode child : children) {
            card.append("    ").append(child.id()).append("  [").append(child.kind()).append("]  ").append(child.name());
            String summary = child.facts().get("summary");
            if (summary != null) card.append("  - ").append(summary);
            String exemplars = child.facts().get("exemplars");
            if (summary == null && exemplars != null && !exemplars.isBlank()) card.append("  - ").append(exemplars);
            card.append('\n');
        }
        return card.toString();
    }

    private static boolean isNumeric(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) if (!Character.isDigit(value.charAt(i))) return false;
        return true;
    }
}

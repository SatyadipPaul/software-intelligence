package io.softwareintelligence.queryengine;

import io.softwareintelligence.indextree.IndexKind;
import io.softwareintelligence.indextree.IndexNode;
import io.softwareintelligence.indextree.IndexTree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Walks the table of contents to a set of anchors, one level at a time.
 *
 * <p>This is retrieval as navigation rather than as ranking. A step reads one node's card, scores
 * the children it presents, keeps the best few, and repeats. What comes back is not a list of
 * symbols that resembled the question — it is the set of places a reader would have opened, with
 * the path that led to each one.
 *
 * <p>The descent is recorded. A wrong answer from a similarity score is unexplainable; a wrong
 * answer from a descent points at the exact step where the wrong branch won, which is the property
 * that makes this debuggable at all.
 */
public final class TreeNavigator {

    /**
     * One scored candidate, with the reason it scored that way.
     *
     * <p>{@code holdsSubject} is ranked ahead of {@code score} rather than folded into it. A branch
     * that contains something named exactly what the question asked for is not "a bit more
     * relevant" than one that merely uses the same words a lot — it is the branch a reader would
     * open, and no amount of term frequency elsewhere should outvote it.
     */
    public record Scored(IndexNode node, double score, boolean holdsSubject, String reason) {
        static final Comparator<Scored> BEST_FIRST =
                Comparator.comparing(Scored::holdsSubject).reversed()
                        .thenComparing(Comparator.comparingDouble(Scored::score).reversed())
                        .thenComparing(scored -> scored.node().id());
    }

    /** One decision: the card that was read, what it offered, and what was kept. */
    public record Step(String from, List<Scored> considered, List<String> chosen) { }

    /** The result of a descent: where it landed, and how it got there. */
    public record Descent(List<Scored> anchors, List<Step> trace, int cardsRead) {
        public List<String> anchorGraphIds() {
            List<String> ids = new ArrayList<>();
            for (Scored anchor : anchors) {
                String graphId = anchor.node().anchor();
                if (!graphId.isBlank() && !ids.contains(graphId)) ids.add(graphId);
            }
            return List.copyOf(ids);
        }
    }

    /**
     * Chooses which of the presented children to descend into.
     *
     * <p>The contract is the safety property: a chooser receives the ranked children of one card
     * and returns ids drawn from that set. Whatever does the choosing — this class's own scorer, or
     * an assistant reading the card in {@link NavigationSession} — cannot reach a branch it was
     * never shown, and cannot assert anything about the code, because a choice is not a claim.
     */
    public interface Chooser {
        List<String> choose(IndexNode parent, List<Scored> ranked, int keep);
    }

    /** Keeps the highest scoring children. Needs no model, no credentials, and no network. */
    public static final Chooser DETERMINISTIC = (parent, ranked, keep) ->
            ranked.stream().limit(keep).map(scored -> scored.node().id()).toList();

    /** How far a descent may run before it is cut off, whatever the tree's depth. */
    private static final int MAX_DEPTH = 12;

    /** How much of a parent's score a child inherits, so a good branch is worth following. */
    private static final double INHERITANCE = 0.5;

    /**
     * The card index for the tree most recently descended.
     *
     * <p>Building it is linear in the tree, which is nothing for one question and everything for a
     * benchmark: on jackson-databind, rebuilding it per question was most of a 1,002 ms median
     * against flat retrieval's 134 ms. The index is a pure function of the tree, so caching it
     * changes no result - only how many times the same answer is computed.
     */
    private static IndexTree cachedTree;
    private static CardIndex cachedCards;

    private TreeNavigator() { }

    private static synchronized CardIndex cardsFor(IndexTree tree) {
        if (cachedTree != tree) {
            cachedCards = CardIndex.over(tree);
            cachedTree = tree;
        }
        return cachedCards;
    }

    public static Descent descend(IndexTree tree, String question, QueryPlanner.Plan plan, int beam, int maxAnchors) {
        return descend(tree, question, plan, beam, maxAnchors, DETERMINISTIC);
    }

    public static Descent descend(IndexTree tree, String question, QueryPlanner.Plan plan,
                                  int beam, int maxAnchors, Chooser chooser) {
        CardIndex cards = cardsFor(tree);
        List<String> terms = Bm25Index.queryTerms(question);
        String subject = plan.subject().toLowerCase(Locale.ROOT);

        List<Step> trace = new ArrayList<>();
        Map<String, Scored> anchors = new LinkedHashMap<>();
        List<Scored> frontier = List.of(new Scored(tree.root(), 0.0, false, "descent starts at the root"));
        Set<String> visited = new LinkedHashSet<>();
        int cardsRead = 0;

        for (int depth = 0; depth < MAX_DEPTH && !frontier.isEmpty(); depth++) {
            List<Scored> next = new ArrayList<>();
            for (Scored parent : frontier) {
                if (!visited.add(parent.node().id())) continue;
                List<IndexNode> children = tree.children(parent.node());
                cardsRead++;
                if (children.isEmpty() || stopsHere(parent.node(), plan, subject)) {
                    anchor(anchors, parent);
                    continue;
                }
                List<Scored> ranked = rank(cards, terms, subject, plan, parent, children);
                List<String> chosen = chooser.choose(parent.node(), ranked, Math.max(1, beam));
                trace.add(new Step(parent.node().id(), ranked, List.copyOf(chosen)));
                for (String id : chosen) {
                    ranked.stream().filter(scored -> scored.node().id().equals(id)).findFirst().ifPresent(next::add);
                }
                // Descending has to earn its place. When no child beats the branch itself, the
                // branch is where the answer lives - "which module holds X" is answered by the
                // module, not by a method three levels below it - so it is anchored as well. When a
                // child holds the subject the descent always continues, whatever the text says.
                if (ranked.isEmpty() || (!ranked.get(0).holdsSubject() && parent.score() >= ranked.get(0).score())) {
                    anchor(anchors, parent);
                }
            }
            frontier = next.stream().sorted(Scored.BEST_FIRST).limit(Math.max(1, beam)).toList();
        }

        List<Scored> ranked = anchors.values().stream().sorted(Scored.BEST_FIRST)
                .limit(Math.max(1, maxAnchors)).toList();
        return new Descent(ranked, List.copyOf(trace), cardsRead);
    }

    private static void anchor(Map<String, Scored> anchors, Scored candidate) {
        if (candidate.node().anchor().isBlank()) return;
        Scored existing = anchors.get(candidate.node().id());
        if (existing == null || Scored.BEST_FIRST.compare(candidate, existing) < 0) {
            anchors.put(candidate.node().id(), candidate);
        }
    }

    /**
     * Scores the children of one card.
     *
     * <p>Three signals, in decreasing order of trustworthiness: an exact match on the symbol the
     * question names, the question's terms against the card's text, and what kind of thing the
     * entry is given what kind of question was asked.
     */
    private static List<Scored> rank(CardIndex cards, List<String> terms, String subject,
                                     QueryPlanner.Plan plan, Scored parent, List<IndexNode> children) {
        List<Scored> ranked = new ArrayList<>();
        for (IndexNode child : children) {
            double text = cards.score(terms, child);
            double affinity = affinity(plan.kind(), child);
            boolean isSubject = names(child, subject);
            boolean holds = isSubject || cards.subtreeHolds(child, subject);
            double score = parent.score() * INHERITANCE + text * affinity;
            StringBuilder reason = new StringBuilder();
            if (isSubject) reason.append("is the subject; ");
            else if (holds) reason.append("holds the subject; ");
            reason.append(String.format(Locale.ROOT, "text %.2f x affinity %.2f", text, affinity));
            if (parent.score() > 0) reason.append(String.format(Locale.ROOT, " + %.2f inherited", parent.score() * INHERITANCE));
            ranked.add(new Scored(child, score, holds, reason.toString()));
        }
        ranked.sort(Scored.BEST_FIRST);
        return ranked;
    }

    /**
     * Stops the descent where the question's own shape says the answer lives. A structural question
     * is answered by a module, not by a method three levels below it, and descending anyway would
     * spend the beam on detail the asker did not want.
     */
    private static boolean stopsHere(IndexNode node, QueryPlanner.Plan plan, String subject) {
        if (plan.kind() == QueryPlanner.QueryKind.STRUCTURE
                && (node.kind() == IndexKind.MODULE || node.kind() == IndexKind.PACKAGE)) return true;
        // An exact name match is proof enough: the asker named this symbol, so its own context is
        // the answer, and its members are reachable from it anyway.
        return names(node, subject) && (node.kind() == IndexKind.TYPE || node.kind() == IndexKind.MEMBER);
    }

    private static boolean names(IndexNode node, String subject) {
        if (subject.isBlank()) return false;
        String name = node.name().toLowerCase(Locale.ROOT);
        if (name.equals(subject)) return true;
        int dot = name.lastIndexOf('.');
        return dot >= 0 && name.substring(dot + 1).equals(subject);
    }

    /**
     * What kind of entry answers what kind of question. Mirrors the retrieval prior the flat index
     * already applies to symbols, extended with the levels only a tree has.
     */
    private static double affinity(QueryPlanner.QueryKind kind, IndexNode node) {
        IndexKind entry = node.kind();
        String entity = node.facts().getOrDefault("kind", "");
        return switch (kind) {
            case ENDPOINT -> switch (entry) {
                case ENDPOINT -> 1.8;
                case CAPABILITY -> 1.5;
                case TYPE -> "CONTROLLER".equals(entity) ? 1.4 : 0.9;
                case MEMBER -> 0.8;
                default -> 0.9;
            };
            case PERSISTENCE -> switch (entry) {
                case TABLE -> 1.8;
                case TYPE -> "ENTITY".equals(entity) || "REPOSITORY_COMPONENT".equals(entity) ? 1.5 : 0.9;
                case CAPABILITY -> 1.1;
                default -> 0.9;
            };
            case SECURITY -> switch (entry) {
                case ENDPOINT, CAPABILITY -> 1.3;
                case TYPE -> 1.1;
                default -> 0.9;
            };
            case STRUCTURE -> switch (entry) {
                case MODULE -> 1.8;
                case PACKAGE -> 1.5;
                case CAPABILITY -> 1.2;
                case MEMBER -> 0.5;
                default -> 0.9;
            };
            case IMPACT -> switch (entry) {
                case TYPE -> 1.4;
                case MEMBER -> 1.1;
                case ENDPOINT -> 1.0;
                case CAPABILITY -> 0.8;
                default -> 1.0;
            };
            case LOOKUP -> switch (entry) {
                case TYPE -> 1.3;
                case MEMBER -> 1.1;
                case GROUP -> 1.0;
                default -> 1.0;
            };
        };
    }
}

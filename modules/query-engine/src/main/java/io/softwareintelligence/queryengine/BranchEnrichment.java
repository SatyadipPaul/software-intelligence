package io.softwareintelligence.queryengine;

import io.softwareintelligence.indextree.IndexKind;
import io.softwareintelligence.indextree.IndexNode;
import io.softwareintelligence.indextree.IndexTree;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.GraphNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ranks the branches of the index tree by how much a written summary there would be worth.
 *
 * <p>{@link EnrichmentPlanner} ranks symbols by how much is known about them. This ranks
 * <em>decisions</em>: a summary on a branch is read every time a descent reaches that card, so one
 * good sentence on a module that presents twenty choices over four thousand symbols steers far more
 * questions than a paragraph on a leaf method that is read once and only by whoever already found
 * it.
 *
 * <p>Three things make a branch worth the tokens, and the third is the one a symbol-ranking planner
 * cannot see:
 *
 * <ul>
 *   <li><b>Reach</b> — how many symbols sit below it, so how many questions could route through it.
 *   <li><b>Choices</b> — how many children its card presents. A branch with one child asks nothing
 *       of the reader, so describing it disambiguates nothing.
 *   <li><b>Opacity</b> — how little the card already says. {@code payments} is a name a reader can
 *       act on; {@code util}, {@code internal}, {@code common} and {@code impl} are not, and they
 *       are exactly the branches a descent guesses at today.
 * </ul>
 *
 * <p>Nothing here calls a model, and nothing here writes to the graph. It produces candidates for
 * the same budget, packet, and verification path that already exists, so a summary arrives as a
 * confidence-capped {@code claim.summary} on the graph node the branch covers — which
 * {@code IndexTreeBuilder} then reads back onto the card.
 */
public final class BranchEnrichment {

    /**
     * Names that tell a reader nothing about what is inside. Deliberately a short, conservative
     * list of Java-ecosystem conventions rather than a general vocabulary: a wrong entry here only
     * misdirects a budget, but a long list would start calling meaningful domain names generic.
     */
    private static final Set<String> UNINFORMATIVE = Set.of(
            "util", "utils", "internal", "common", "core", "impl", "misc", "shared", "base",
            "support", "helper", "helpers", "model", "models", "api", "spi", "type", "types",
            "data", "lang", "std", "ext", "extra", "other", "root", "main", "src", "default");

    private BranchEnrichment() { }

    public static EnrichmentPlanner.Plan plan(CodeGraph graph, IndexTree tree, EnrichmentPlanner.Budget budget) {
        List<EnrichmentPlanner.Candidate> ranked = rank(graph, tree);
        List<EnrichmentPlanner.Candidate> selected = new ArrayList<>();
        List<EnrichmentPlanner.Candidate> skipped = new ArrayList<>();
        int spent = 0;
        for (EnrichmentPlanner.Candidate candidate : ranked) {
            if (spent + candidate.estimatedTokens() <= budget.maxTokens()) {
                selected.add(candidate);
                spent += candidate.estimatedTokens();
            } else {
                skipped.add(candidate);
            }
        }
        return new EnrichmentPlanner.Plan(List.copyOf(selected), List.copyOf(skipped), spent, budget.estimatedCost(spent));
    }

    static List<EnrichmentPlanner.Candidate> rank(CodeGraph graph, IndexTree tree) {
        Map<String, Integer> reach = subtreeSizes(tree);
        List<EnrichmentPlanner.Candidate> candidates = new ArrayList<>();
        for (IndexNode node : tree.nodes()) {
            // A group is a shelf, not a thing: it stands for no graph node, so there is nothing a
            // claim could be pinned to. A leaf presents no choice. Neither can be described here.
            if (node.kind() == IndexKind.ROOT || node.kind() == IndexKind.GROUP) continue;
            if (node.anchor().isBlank() || node.children().size() < 2) continue;
            GraphNode covered = graph.node(node.anchor()).orElse(null);
            if (covered == null) continue;
            // Already described: a second opinion on the same branch buys nothing.
            if (covered.attributes().containsKey("claim.summary")) continue;

            int symbols = reach.getOrDefault(node.id(), 1);
            int choices = node.children().size();
            boolean generic = UNINFORMATIVE.contains(lastSegment(node.name()));
            boolean unexemplified = node.facts().getOrDefault("exemplars", "").isBlank();
            // A test class is a branch with plenty of choices below it and no questions routed
            // through it. Ranking it by fan-out alone put OwnerControllerTests fifth on Petclinic,
            // which is the same mistake the symbol planner makes when it offers to describe a2q.
            boolean test = isTest(node, covered);

            double opacity = 1.0 + (generic ? 1.0 : 0.0) + (unexemplified ? 0.5 : 0.0);
            double score = Math.log(1 + symbols) * Math.log(1 + choices) * opacity * kindWeight(node.kind());
            if (test) score *= 0.1;
            if (score <= 0) continue;

            String reason = String.format(Locale.ROOT, "%d choices over %d symbols%s%s%s",
                    choices, symbols, generic ? ", name says nothing" : "",
                    unexemplified ? ", card has no exemplars" : "", test ? ", test code" : "");
            candidates.add(new EnrichmentPlanner.Candidate(node.anchor(),
                    Math.round(score * 100.0) / 100.0, estimateTokens(node), reason));
        }
        return candidates.stream()
                .sorted(Comparator.comparingDouble(EnrichmentPlanner.Candidate::score).reversed()
                        .thenComparing(EnrichmentPlanner.Candidate::id))
                .toList();
    }

    /**
     * How much a summary at this level steers. A capability or a module sits above whole subsystems;
     * a type sits above its own methods, where a reader who arrived already knows roughly where
     * they are.
     */
    private static double kindWeight(IndexKind kind) {
        return switch (kind) {
            case CAPABILITY, MODULE -> 1.0;
            case PACKAGE -> 0.9;
            case TYPE -> 0.6;
            default -> 0.4;
        };
    }

    /** Subtree sizes for every node, computed iteratively so a deep tree cannot overflow the stack. */
    private static Map<String, Integer> subtreeSizes(IndexTree tree) {
        Map<String, Integer> sizes = new HashMap<>();
        record Frame(IndexNode node, boolean entered) { }
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(tree.root(), false));
        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            if (frame.entered()) {
                int size = 1;
                for (IndexNode child : tree.children(frame.node())) size += sizes.getOrDefault(child.id(), 0);
                sizes.put(frame.node().id(), size);
                continue;
            }
            if (sizes.containsKey(frame.node().id())) continue;
            stack.push(new Frame(frame.node(), true));
            for (IndexNode child : tree.children(frame.node())) stack.push(new Frame(child, false));
        }
        return sizes;
    }

    /**
     * Whether this branch is test code, read from where it is declared rather than from its name
     * alone: a repository may legitimately ship a class called {@code TestSupport} in production.
     */
    private static boolean isTest(IndexNode node, GraphNode covered) {
        String file = covered.provenance().file().replace('\\', '/');
        if (file.contains("/src/test/") || file.startsWith("src/test/") || file.contains("/src/testFixtures/")) return true;
        String name = lastSegment(node.name());
        return name.endsWith("test") || name.endsWith("tests") || name.endsWith("testcase") || name.endsWith("it");
    }

    private static String lastSegment(String name) {
        int dot = name.lastIndexOf('.');
        int slash = name.lastIndexOf('/');
        int cut = Math.max(dot, slash);
        return (cut < 0 ? name : name.substring(cut + 1)).toLowerCase(Locale.ROOT);
    }

    /** A stable estimate of what describing one branch costs, in tokens. */
    private static int estimateTokens(IndexNode node) {
        int facts = node.facts().entrySet().stream()
                .mapToInt(entry -> (entry.getKey().length() + entry.getValue().length()) / 4).sum();
        return 140 + node.id().length() / 4 + facts;
    }
}

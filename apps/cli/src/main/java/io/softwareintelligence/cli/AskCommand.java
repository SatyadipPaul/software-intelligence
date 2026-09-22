package io.softwareintelligence.cli;

import io.softwareintelligence.indextree.IndexTree;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.ContextPacket;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.GraphQueries;
import io.softwareintelligence.queryengine.Bm25Index;
import io.softwareintelligence.queryengine.NavigationSession;
import io.softwareintelligence.queryengine.QueryPlanner;
import io.softwareintelligence.queryengine.RetrievalMode;
import io.softwareintelligence.queryengine.TreeNavigator;
import io.softwareintelligence.queryengine.VerifiedAnswer;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

@CommandLine.Command(mixinStandardHelpOptions = true, name = "ask",
        description = "Answer a question from the graph, citing source evidence and withholding anything unsupported.")
final class AskCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "REPOSITORY",
            description = "Java repository or graph file. Omit it to use the current directory.")
    private String first;

    @CommandLine.Parameters(index = "1", arity = "0..1", paramLabel = "QUESTION",
            description = "Question in plain language")
    private String second;

    /** Both resolved in call() from the positionals above, in either accepted order. */
    private Path repository;
    private String question;
    @CommandLine.Mixin private AnalysisOptions options;

    @CommandLine.Option(names = "--budget", description = "Token budget for the context packet", defaultValue = "1500")
    private int budget;

    @CommandLine.Option(names = "--retrieve", description = "How many symbols BM25 retrieval returns", defaultValue = "10")
    private int retrieve;

    @CommandLine.Option(names = "--retrieval", defaultValue = "HYBRID",
            description = "Where anchors come from: ${COMPLETION-CANDIDATES} (default HYBRID)")
    private RetrievalMode retrieval;

    @CommandLine.Option(names = "--anchors", description = "How many anchors the answer may rest on", defaultValue = "1")
    private int maxAnchors;

    @CommandLine.Option(names = "--beam", description = "How wide a tree descent keeps its frontier", defaultValue = "4")
    private int beam;

    @CommandLine.Option(names = "--index", description = "Use a pinned tree file rather than deriving one")
    private Path indexFile;

    @CommandLine.Option(names = "--from-session",
            description = "Answer from the anchors an assistant chose in a `navigate` session")
    private Path session;

    @CommandLine.Option(names = "--explain", description = "Print the descent that produced the anchors")
    private boolean explain;

    @Override public Integer call() throws Exception {
        Target target = options.target(first, second, "question", this);
        repository = target.repository();
        question = target.subject();
        CodeGraph graph = options.analyze(repository);
        Bm25Index index = Bm25Index.over(graph);
        IndexTree tree = retrieval.needsTree() || session != null ? TreeOptions.load(graph, indexFile) : null;

        List<GraphNode> anchors;
        List<ContextPacket> contexts;
        QueryPlanner.Plan plan = QueryPlanner.classify(question);
        Optional<TreeNavigator.Descent> descent = Optional.empty();

        if (session != null) {
            anchors = sessionAnchors(graph, tree);
            if (anchors.isEmpty()) {
                System.out.println("That descent has not anchored anywhere yet, so there is nothing to answer from.");
                return 0;
            }
            contexts = anchors.stream().map(node -> GraphQueries.context(graph, node, plan.depth())).toList();
            System.out.printf("PLAN: %s (depth %d) - anchors chosen by an assistant descent%n", plan.kind(), plan.depth());
        } else {
            QueryPlanner.Answerable answerable = QueryPlanner.plan(graph, index, tree, question,
                    retrieve, retrieval, maxAnchors, beam);
            anchors = answerable.anchors();
            contexts = answerable.contexts();
            descent = answerable.descent();
            System.out.printf("PLAN: %s (depth %d) - %s%n", answerable.plan().kind(), answerable.plan().depth(),
                    answerable.plan().rationale());
            System.out.printf("RETRIEVAL: %s%s%n", retrieval,
                    descent.map(found -> ", " + found.cardsRead() + " cards read").orElse(""));
            System.out.println("RETRIEVED:");
            answerable.retrieved().forEach(hit -> System.out.printf("  %-60s score=%.3f%n", hit.node().id(), hit.score()));
        }

        if (explain) descent.ifPresent(AskCommand::printDescent);

        if (anchors.isEmpty() || contexts.isEmpty()) {
            System.out.println("\nNo symbol in this repository anchors that question, so no answer is given.");
            return 0;
        }

        System.out.println("\nANCHORS:");
        anchors.forEach(anchor -> System.out.println("  " + anchor.id()));

        Set<String> anchorIds = new java.util.LinkedHashSet<>(anchors.stream().map(GraphNode::id).toList());
        ContextPacket packet = QueryPlanner.compress(QueryPlanner.merge(contexts), anchorIds, budget);
        System.out.printf("%nCONTEXT: subject=%s anchors=%d callers=%d endpoints=%d evidence=%d (~%d tokens, budget %d)%n",
                packet.subject().id(), anchors.size(), packet.callers().size(), packet.endpoints().size(),
                packet.evidence().size(), QueryPlanner.estimateTokens(packet), budget);

        // Claims are derived from the packet, then verified by the same gate any generated claim
        // would face. Nothing is printed that the graph cannot support.
        List<VerifiedAnswer.Claim> claims = VerifiedAnswer.claimsFrom(packet, anchorIds);
        VerifiedAnswer.Answer answer = VerifiedAnswer.verify(graph, question, claims);
        System.out.println();
        System.out.print(VerifiedAnswer.render(answer));
        return 0;
    }

    private List<GraphNode> sessionAnchors(CodeGraph graph, IndexTree tree) throws java.io.IOException {
        NavigationSession.State state = NavigationSession.read(session);
        NavigationSession.requireCurrent(state, tree);
        List<GraphNode> anchors = new ArrayList<>();
        for (String id : NavigationSession.anchorGraphIds(tree, state)) graph.node(id).ifPresent(anchors::add);
        return anchors;
    }

    /** The descent, printed as the decisions it was: what was on each card, and what won. */
    private static void printDescent(TreeNavigator.Descent descent) {
        System.out.println("\nDESCENT:");
        for (TreeNavigator.Step step : descent.trace()) {
            System.out.println("  at " + step.from());
            for (TreeNavigator.Scored scored : step.considered().stream().limit(5).toList()) {
                System.out.printf("    %s %-52s %.3f  (%s)%n",
                        step.chosen().contains(scored.node().id()) ? "->" : "  ",
                        scored.node().id(), scored.score(), scored.reason());
            }
        }
    }
}

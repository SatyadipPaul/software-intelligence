package io.softwareintelligence.cli;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.ContextPacket;
import io.softwareintelligence.queryengine.Bm25Index;
import io.softwareintelligence.queryengine.QueryPlanner;
import io.softwareintelligence.queryengine.VerifiedAnswer;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "ask",
        description = "Answer a question from the graph, citing source evidence and withholding anything unsupported.")
final class AskCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Java repository to inspect") private Path repository;
    @CommandLine.Parameters(index = "1", description = "Question in plain language") private String question;
    @CommandLine.Mixin private AnalysisOptions options;

    @CommandLine.Option(names = "--budget", description = "Token budget for the context packet", defaultValue = "1500")
    private int budget;

    @CommandLine.Option(names = "--retrieve", description = "How many symbols BM25 retrieval returns", defaultValue = "10")
    private int retrieve;

    @Override public Integer call() throws Exception {
        CodeGraph graph = options.analyze(repository);
        Bm25Index index = Bm25Index.over(graph);
        QueryPlanner.Answerable answerable = QueryPlanner.plan(graph, index, question, retrieve);

        System.out.printf("PLAN: %s (depth %d) - %s%n", answerable.plan().kind(), answerable.plan().depth(), answerable.plan().rationale());
        System.out.println("RETRIEVED:");
        answerable.retrieved().forEach(hit -> System.out.printf("  %-60s score=%.3f%n", hit.node().id(), hit.score()));

        if (answerable.subject().isEmpty() || answerable.context().isEmpty()) {
            System.out.println("\nNo symbol in this repository anchors that question, so no answer is given.");
            return 0;
        }
        ContextPacket packet = QueryPlanner.compress(answerable.context().get(), budget);
        System.out.printf("%nCONTEXT: subject=%s callers=%d endpoints=%d evidence=%d (~%d tokens, budget %d)%n",
                packet.subject().id(), packet.callers().size(), packet.endpoints().size(), packet.evidence().size(),
                QueryPlanner.estimateTokens(packet), budget);

        // Claims are derived from the packet, then verified by the same gate any generated claim
        // would face. Nothing is printed that the graph cannot support.
        List<VerifiedAnswer.Claim> claims = VerifiedAnswer.claimsFrom(packet);
        VerifiedAnswer.Answer answer = VerifiedAnswer.verify(graph, question, claims);
        System.out.println();
        System.out.print(VerifiedAnswer.render(answer));
        return 0;
    }
}

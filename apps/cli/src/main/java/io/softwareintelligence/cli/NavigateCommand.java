package io.softwareintelligence.cli;

import io.softwareintelligence.indextree.IndexTree;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.queryengine.NavigationSession;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "navigate",
        description = "Walk the index tree one level at a time, so an assistant can choose the branches. Calls no model.")
final class NavigateCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Java repository or graph file") private Path repository;

    @CommandLine.Parameters(index = "1", arity = "0..1", description = "Question, when starting a descent")
    private String question;

    @CommandLine.Option(names = "--session", required = true,
            description = "Session file; created when it does not exist, advanced when it does")
    private Path session;

    @CommandLine.Option(names = "--choose", split = ",",
            description = "Index ids the assistant chose from the last cards, comma separated")
    private List<String> choose;

    @CommandLine.Option(names = "--choices",
            description = "File holding the assistant's answer: {\"chosen\": [\"<id>\", ...]}")
    private Path choices;

    @CommandLine.Option(names = {"-o", "--output"}, description = "Write the next packet here as well as printing it")
    private Path output;

    @CommandLine.Option(names = "--index", description = "Use a pinned tree file rather than deriving one")
    private Path indexFile;

    @CommandLine.Mixin private AnalysisOptions options;

    @Override public Integer call() throws Exception {
        CodeGraph graph = options.analyze(repository);
        IndexTree tree = TreeOptions.load(graph, indexFile);

        NavigationSession.State state;
        if (Files.exists(session)) {
            state = NavigationSession.read(session);
            NavigationSession.requireCurrent(state, tree);
            List<String> chosen = chosenIds();
            if (!chosen.isEmpty()) state = NavigationSession.advance(tree, state, chosen);
        } else {
            if (question == null || question.isBlank()) {
                System.err.println("error: starting a descent needs a question");
                return CommandLine.ExitCode.USAGE;
            }
            state = NavigationSession.start(tree, question);
        }
        NavigationSession.write(state, session);

        if (state.complete()) {
            List<String> anchors = NavigationSession.anchorGraphIds(tree, state);
            System.out.printf("DESCENT COMPLETE after %d step(s): %d anchor(s)%n", state.step(), anchors.size());
            anchors.forEach(anchor -> System.out.println("  " + anchor));
            System.out.printf("%nAnswer from them with:%n  repo-intel ask %s \"%s\" --from-session %s%n",
                    repository, state.question(), session);
            return 0;
        }

        String packet = NavigationSession.packet(tree, state);
        if (output != null) {
            if (output.getParent() != null) Files.createDirectories(output.getParent());
            Files.writeString(output, packet);
            System.out.printf("wrote %s (step %d)%n", output, state.step());
        }
        System.out.print(packet);
        System.out.printf("%nAnswer with the ids, then:%n  repo-intel navigate %s --session %s --choose <id>,<id>%n",
                repository, session);
        return 0;
    }

    private List<String> chosenIds() throws java.io.IOException {
        if (choices != null) return NavigationSession.readChoices(choices);
        return choose == null ? List.of() : choose.stream().map(String::trim).filter(id -> !id.isBlank()).toList();
    }
}

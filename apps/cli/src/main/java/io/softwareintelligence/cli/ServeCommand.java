package io.softwareintelligence.cli;

import io.softwareintelligence.session.AnalysisSession;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@CommandLine.Command(mixinStandardHelpOptions = true, name = "serve",
        description = {"Serve one repository to an assistant over the Model Context Protocol, on stdio.",
                "The graph is analyzed once and held, and rebuilt when source files change, so every "
                        + "question after the first costs milliseconds rather than a fresh analysis.",
                "",
                "Configure it in an MCP client as a command, for example:",
                "  {\"command\": \"repo-intel\", \"args\": [\"serve\", \"-C\", \"/path/to/repo\"]}",
                "",
                "Heap: analyzing about 1,400 source files needs 1 GB. Set JAVA_OPTS=-Xmx1536m for a "
                        + "repository of that size."})
final class ServeCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "REPOSITORY",
            description = "Java repository to serve. Omit it to use the current directory.")
    private String repositoryArgument;

    @CommandLine.Mixin private AnalysisOptions options;

    @Override public Integer call() throws Exception {
        Path repository = options.repository(repositoryArgument);
        if (AnalysisOptions.isGraphFile(repository)) {
            // A graph file cannot be refreshed: there is no source to fingerprint, so the server
            // could never know it had gone stale. Refusing is better than serving it as if current.
            throw new CommandLine.ParameterException(new CommandLine(this),
                    "serve needs a source directory, not a graph file: a graph file has no source to watch for changes");
        }
        if (!Files.isDirectory(repository)) {
            throw new CommandLine.ParameterException(new CommandLine(this), "not a directory: " + repository);
        }

        // Stdout carries protocol messages and nothing else. Keep the real one for the server, and
        // send everything else that would have gone there - any library's stray println - to
        // stderr, where a client ignores it instead of disconnecting.
        PrintStream protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        System.setOut(System.err);

        AnalysisSession.Request request = options.request(repository);
        System.err.printf("repo-intel %s: analyzing %s%n", Main.Version.current(), repository.toAbsolutePath().normalize());
        try (McpServer server = new McpServer(() -> {
            long started = System.nanoTime();
            AnalysisSession session = AnalysisSession.open(request);
            System.err.printf("repo-intel: ready, %d nodes from %d source files in %d ms%n",
                    session.graph().nodes().size(), session.fingerprint().files(),
                    (System.nanoTime() - started) / 1_000_000);
            return session;
        }, Main.Version.current())) {
            server.serve(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), protocol);
        }
        return 0;
    }
}

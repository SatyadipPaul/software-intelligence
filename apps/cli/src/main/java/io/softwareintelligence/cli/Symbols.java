package io.softwareintelligence.cli;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.GraphQueries;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.List;
import java.util.Optional;

/** Shared symbol lookup so every command reports an ambiguous query the same way. */
final class Symbols {
    private static final int REPORTED_ALTERNATIVES = 5;

    private Symbols() { }

    /**
     * Resolves a query and warns on stderr when other symbols matched equally well, so a user is
     * never silently shown the analysis of a symbol they did not mean.
     */
    static GraphNode resolve(CodeGraph graph, String query, Object command) {
        return resolve(graph, query, System.err).orElseThrow(() ->
                new CommandLine.ParameterException(new CommandLine(command), "No symbol matched: " + query));
    }

    /**
     * Resolves a query, writing any ambiguity warning to {@code warnings}.
     *
     * <p>The stream is the caller's choice because stderr is not always read. A server's client
     * never sees the server's stderr, so there the warning has to travel inside the answer - or the
     * client is shown the analysis of a symbol it did not ask about, with nothing to say so.
     */
    static Optional<GraphNode> resolve(CodeGraph graph, String query, PrintStream warnings) {
        Optional<GraphQueries.SymbolMatch> found = GraphQueries.resolveSymbol(graph, query);
        if (found.isEmpty()) return Optional.empty();
        GraphQueries.SymbolMatch match = found.get();
        if (match.ambiguous()) {
            List<GraphNode> shown = match.alternatives().subList(0, Math.min(REPORTED_ALTERNATIVES, match.alternatives().size()));
            warnings.printf("warning: %d symbols matched '%s'; using %s%n", match.alternatives().size() + 1, query, match.node().id());
            shown.forEach(node -> warnings.println("  also matched: " + node.id()));
            if (match.alternatives().size() > shown.size()) {
                warnings.printf("  ... and %d more%n", match.alternatives().size() - shown.size());
            }
        }
        return Optional.of(match.node());
    }
}

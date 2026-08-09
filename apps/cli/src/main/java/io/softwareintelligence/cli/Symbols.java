package io.softwareintelligence.cli;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.GraphQueries;
import picocli.CommandLine;

import java.util.List;

/** Shared symbol lookup so every command reports an ambiguous query the same way. */
final class Symbols {
    private static final int REPORTED_ALTERNATIVES = 5;

    private Symbols() { }

    /**
     * Resolves a query and warns on stderr when other symbols matched equally well, so a user is
     * never silently shown the analysis of a symbol they did not mean.
     */
    static GraphNode resolve(CodeGraph graph, String query, Object command) {
        GraphQueries.SymbolMatch match = GraphQueries.resolveSymbol(graph, query)
                .orElseThrow(() -> new CommandLine.ParameterException(new CommandLine(command), "No symbol matched: " + query));
        if (match.ambiguous()) {
            List<GraphNode> shown = match.alternatives().subList(0, Math.min(REPORTED_ALTERNATIVES, match.alternatives().size()));
            System.err.printf("warning: %d symbols matched '%s'; using %s%n", match.alternatives().size() + 1, query, match.node().id());
            shown.forEach(node -> System.err.println("  also matched: " + node.id()));
            if (match.alternatives().size() > shown.size()) {
                System.err.printf("  ... and %d more%n", match.alternatives().size() - shown.size());
            }
        }
        return match.node();
    }
}

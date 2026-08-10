package io.softwareintelligence.cli;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.ContextPacket;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.GraphQueries;
import io.softwareintelligence.model.GraphSnapshot;
import io.softwareintelligence.visualization.GraphExports;
import io.softwareintelligence.visualization.GraphHtmlView;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "visualize",
        description = "Write a self-contained view of the graph: interactive HTML, or GraphML/DOT for other tools.")
final class VisualizeCommand implements Callable<Integer> {
    enum Format { HTML, GRAPHML, DOT, CYTOSCAPE }

    enum Scope { ALL, OPERATIONAL, ARCHITECTURE, SYMBOL }

    @CommandLine.Parameters(index = "0", description = "Java repository, or a snapshot JSON written by `snapshot`")
    private Path source;

    @CommandLine.Option(names = {"-o", "--output"}, description = "Output file", defaultValue = "repo-graph.html")
    private Path output;

    @CommandLine.Option(names = "--format", description = "HTML, GRAPHML, DOT, CYTOSCAPE", defaultValue = "HTML")
    private Format format;

    @CommandLine.Option(names = "--scope",
            description = "ALL, OPERATIONAL (endpoints/services/tables/topics), ARCHITECTURE (modules and capabilities), SYMBOL",
            defaultValue = "OPERATIONAL")
    private Scope scope;

    @CommandLine.Option(names = "--symbol", description = "Focus symbol, required for --scope SYMBOL")
    private String symbol;

    @CommandLine.Option(names = "--depth", description = "Traversal depth for --scope SYMBOL", defaultValue = "3")
    private int depth;

    @CommandLine.Option(names = "--max-nodes", description = "Most nodes to draw before keeping only the highest-degree", defaultValue = "1200")
    private int maxNodes;

    @CommandLine.Mixin private AnalysisOptions options;

    /** The kinds that answer operational questions; the ones a reader opens a graph to see. */
    private static final Set<EntityKind> OPERATIONAL = Set.of(
            EntityKind.CONTROLLER, EntityKind.SERVICE, EntityKind.REPOSITORY_COMPONENT, EntityKind.ENTITY,
            EntityKind.CONFIGURATION, EntityKind.ENDPOINT, EntityKind.TOPIC, EntityKind.DATABASE_TABLE,
            EntityKind.SECURITY_GUARD, EntityKind.EXTERNAL_SERVICE, EntityKind.CONFIGURATION_PROPERTY,
            EntityKind.WORKFLOW, EntityKind.BUSINESS_CAPABILITY, EntityKind.INTERFACE, EntityKind.TYPE);

    private static final Set<EntityKind> ARCHITECTURE = Set.of(
            EntityKind.MODULE, EntityKind.BUSINESS_CAPABILITY, EntityKind.WORKFLOW,
            EntityKind.ENDPOINT, EntityKind.DATABASE_TABLE, EntityKind.TOPIC, EntityKind.EXTERNAL_SERVICE);

    @Override public Integer call() throws Exception {
        CodeGraph full = options.analyze(source);
        CodeGraph view = switch (scope) {
            case ALL -> full;
            case OPERATIONAL -> filter(full, OPERATIONAL);
            case ARCHITECTURE -> filter(full, ARCHITECTURE);
            case SYMBOL -> around(full);
        };
        Path destination = output.toAbsolutePath();
        if (destination.getParent() != null) Files.createDirectories(destination.getParent());

        if (format == Format.HTML) {
            String title = source.toAbsolutePath().getFileName().toString()
                    + " - " + scope.name().toLowerCase(java.util.Locale.ROOT) + (scope == Scope.SYMBOL ? " view of " + symbol : " view");
            GraphHtmlView.View rendered = GraphHtmlView.render(view, title, maxNodes);
            Files.writeString(destination, rendered.html());
            System.out.printf("Wrote %s (%d/%d nodes, %d/%d edges)%n", destination,
                    rendered.renderedNodes(), rendered.totalNodes(), rendered.renderedEdges(), rendered.totalEdges());
            if (rendered.truncated()) {
                System.out.printf("Kept the %d highest-degree nodes; raise --max-nodes or narrow --scope to see more.%n",
                        rendered.renderedNodes());
            }
            return 0;
        }
        String text = switch (format) {
            case GRAPHML -> GraphExports.graphml(view);
            case DOT -> GraphExports.dot(view);
            case CYTOSCAPE -> GraphExports.cytoscape(view);
            case HTML -> throw new IllegalStateException("handled above");
        };
        Files.writeString(destination, text);
        System.out.printf("Wrote %s (%d nodes, %d edges)%n", destination, view.nodes().size(), view.edges().size());
        return 0;
    }

    /** Keeps the chosen kinds and every relationship that still has both ends inside the view. */
    private static CodeGraph filter(CodeGraph graph, Set<EntityKind> kinds) {
        CodeGraph view = new CodeGraph();
        graph.nodes().stream().filter(node -> kinds.contains(node.kind())).forEach(node -> view.upsertNode(node, true));
        for (GraphEdge edge : graph.edges()) {
            if (view.node(edge.from()).isPresent() && view.node(edge.to()).isPresent()) view.addEdge(edge);
        }
        return view;
    }

    /** The neighbourhood of one symbol: exactly the context packet, drawn instead of serialized. */
    private CodeGraph around(CodeGraph graph) {
        if (symbol == null || symbol.isBlank()) {
            throw new CommandLine.ParameterException(new CommandLine(this), "--scope SYMBOL requires --symbol");
        }
        GraphNode subject = Symbols.resolve(graph, symbol, this);
        ContextPacket packet = GraphQueries.context(graph, subject, depth);
        CodeGraph view = new CodeGraph();
        view.upsertNode(subject, true);
        packet.callers().forEach(node -> view.upsertNode(node, true));
        packet.endpoints().forEach(node -> view.upsertNode(node, true));
        packet.dependencies().forEach(node -> view.upsertNode(node, true));
        for (GraphEdge edge : packet.evidence()) {
            graph.node(edge.from()).ifPresent(node -> view.upsertNode(node, true));
            graph.node(edge.to()).ifPresent(node -> view.upsertNode(node, true));
        }
        packet.evidence().forEach(view::addEdge);
        return view;
    }
}

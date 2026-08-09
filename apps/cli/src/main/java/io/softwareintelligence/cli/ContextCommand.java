package io.softwareintelligence.cli;

import io.softwareintelligence.analyzer.java.JavaRepositoryAnalyzer;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.ContextPacket;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.GraphQueries;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "context", description = "Create a minimum-sufficient evidence packet for a symbol.")
final class ContextCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Java repository to inspect") private Path repository;
    @CommandLine.Parameters(index = "1", description = "Graph symbol id, name, or id suffix") private String symbol;
    @CommandLine.Option(names = "-d", description = "Maximum impact depth", defaultValue = "3") private int depth;
    @CommandLine.Option(names = "-o", description = "Output JSON file", defaultValue = "context-packet.json") private Path output;
    @CommandLine.Option(names = "--classpath", description = "Classpath entries separated by the platform path separator") private String classpath;

    @Override public Integer call() throws Exception {
        CodeGraph graph = new JavaRepositoryAnalyzer().analyze(repository, classpathEntries());
        GraphNode subject = GraphQueries.findSymbol(graph, symbol).orElseThrow(() -> new CommandLine.ParameterException(new CommandLine(this), "No symbol matched: " + symbol));
        ContextPacket packet = GraphQueries.context(graph, subject, depth);
        Path destination = output.toAbsolutePath();
        if (destination.getParent() != null) java.nio.file.Files.createDirectories(destination.getParent());
        java.nio.file.Files.writeString(destination, json(packet));
        System.out.printf("Wrote context packet: callers=%d endpoints=%d dependencies=%d evidence=%d to %s%n",
                packet.callers().size(), packet.endpoints().size(), packet.dependencies().size(), packet.evidence().size(), destination);
        return 0;
    }

    private List<Path> classpathEntries() {
        if (classpath == null || classpath.isBlank()) return List.of();
        return Arrays.stream(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))).filter(value -> !value.isBlank()).map(Path::of).toList();
    }

    private static String json(ContextPacket packet) {
        StringBuilder value = new StringBuilder("{\n  \"subject\":").append(node(packet.subject())).append(",\n  \"callers\":[");
        appendNodes(value, packet.callers());
        value.append("],\n  \"endpoints\":["); appendNodes(value, packet.endpoints());
        value.append("],\n  \"dependencies\":["); appendNodes(value, packet.dependencies());
        value.append("],\n  \"evidence\":[");
        for (int i = 0; i < packet.evidence().size(); i++) { if (i > 0) value.append(','); value.append(edge(packet.evidence().get(i))); }
        return value.append("]\n}\n").toString();
    }
    private static void appendNodes(StringBuilder value, List<GraphNode> nodes) { for (int i = 0; i < nodes.size(); i++) { if (i > 0) value.append(','); value.append(node(nodes.get(i))); } }
    private static String node(GraphNode node) { return "{\"id\":\"" + q(node.id()) + "\",\"kind\":\"" + node.kind() + "\",\"name\":\"" + q(node.name()) + "\"}"; }
    private static String edge(GraphEdge edge) { return "{\"from\":\"" + q(edge.from()) + "\",\"to\":\"" + q(edge.to()) + "\",\"kind\":\"" + edge.kind() + "\",\"resolver\":\"" + q(edge.provenance().resolver()) + "\",\"confidence\":" + edge.provenance().confidence() + ",\"file\":\"" + q(edge.provenance().file()) + "\",\"line\":" + edge.provenance().line() + "}"; }
    private static String q(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"); }
}

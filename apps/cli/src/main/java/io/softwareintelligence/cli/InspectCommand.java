package io.softwareintelligence.cli;

import io.softwareintelligence.analyzer.java.JavaRepositoryAnalyzer;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "inspect", description = "Analyze a Java repository and write a canonical graph JSON file.")
final class InspectCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Java repository to inspect")
    private Path repository;

    @CommandLine.Option(names = {"-o", "--output"}, defaultValue = "repo-graph.json", description = "Output JSON file")
    private Path output;

    @Override public Integer call() throws IOException {
        CodeGraph graph = new JavaRepositoryAnalyzer().analyze(repository);
        Path destination = output.toAbsolutePath();
        if (destination.getParent() != null) Files.createDirectories(destination.getParent());
        Files.writeString(destination, JsonGraphWriter.write(graph));
        System.out.printf("Wrote %d nodes and %d edges to %s%n", graph.nodes().size(), graph.edges().size(), destination);
        return 0;
    }

    private static final class JsonGraphWriter {
        static String write(CodeGraph graph) {
            StringBuilder json = new StringBuilder("{\n  \"version\": \"0.1\",\n  \"nodes\": [");
            for (int i = 0; i < graph.nodes().size(); i++) { if (i > 0) json.append(','); json.append("\n    ").append(node(graph.nodes().get(i))); }
            json.append("\n  ],\n  \"edges\": [");
            for (int i = 0; i < graph.edges().size(); i++) { if (i > 0) json.append(','); json.append("\n    ").append(edge(graph.edges().get(i))); }
            return json.append("\n  ]\n}\n").toString();
        }
        private static String node(GraphNode node) { return "{\"id\":\"" + q(node.id()) + "\",\"kind\":\"" + node.kind() + "\",\"name\":\"" + q(node.name()) + "\",\"attributes\":" + attributes(node.attributes()) + ",\"provenance\":" + provenance(node.provenance()) + "}"; }
        private static String edge(GraphEdge edge) { return "{\"from\":\"" + q(edge.from()) + "\",\"to\":\"" + q(edge.to()) + "\",\"kind\":\"" + edge.kind() + "\",\"attributes\":" + attributes(edge.attributes()) + ",\"provenance\":" + provenance(edge.provenance()) + "}"; }
        private static String provenance(io.softwareintelligence.model.Provenance value) { return "{\"resolver\":\"" + q(value.resolver()) + "\",\"confidence\":" + value.confidence() + ",\"file\":\"" + q(value.file()) + "\",\"line\":" + value.line() + ",\"column\":" + value.column() + "}"; }
        private static String attributes(java.util.Map<String, String> values) { return values.entrySet().stream().map(entry -> "\"" + q(entry.getKey()) + "\":\"" + q(entry.getValue()) + "\"").collect(java.util.stream.Collectors.joining(",", "{", "}")); }
        private static String q(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"); }
    }
}

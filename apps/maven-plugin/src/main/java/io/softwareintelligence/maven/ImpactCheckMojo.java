package io.softwareintelligence.maven;

import io.softwareintelligence.analyzer.java.JavaRepositoryAnalyzer;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.GraphJsonWriter;
import io.softwareintelligence.model.Json;
import io.softwareintelligence.model.GraphQueries;
import io.softwareintelligence.model.ImpactReport;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Mojo(name = "impact-check", defaultPhase = LifecyclePhase.VERIFY, requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
public final class ImpactCheckMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "repoIntel.symbol")
    private String symbol;

    @Parameter(defaultValue = "${project.build.directory}/repo-intel", required = true)
    private File outputDirectory;

    @Parameter(defaultValue = "4")
    private int depth;

    @Parameter(defaultValue = "-1")
    private int maxImpactedNodes;

    @Override public void execute() throws MojoExecutionException, MojoFailureException {
        if (symbol == null || symbol.isBlank()) {
            getLog().info("repo-intel: impact-check skipped (set -DrepoIntel.symbol=... to enable)");
            return;
        }
        try {
            CodeGraph graph = new JavaRepositoryAnalyzer().analyze(project.getBasedir().toPath(), classpath(), false);
            var match = GraphQueries.resolveSymbol(graph, symbol);
            if (match.isEmpty()) throw new MojoFailureException("No symbol matched: " + symbol);
            var subject = match.get().node();
            if (match.get().ambiguous()) {
                getLog().warn("repo-intel: " + (match.get().alternatives().size() + 1) + " symbols matched '" + symbol
                        + "'; using " + subject.id());
            }
            ImpactReport report = GraphQueries.impact(graph, subject, depth);
            Path directory = outputDirectory.toPath().toAbsolutePath();
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("impact-graph.json"), GraphJsonWriter.write(graph));
            Files.writeString(directory.resolve("impact-report.txt"), format(report));
            Files.writeString(directory.resolve("impact-report.sarif"), sarif(report));
            int impacted = report.direct().size() + report.transitive().size();
            getLog().info("repo-intel: " + subject.name() + " impacts " + impacted + " evidence-backed nodes");
            if (maxImpactedNodes >= 0 && impacted > maxImpactedNodes) {
                throw new MojoFailureException("Impact threshold exceeded: " + impacted + " > " + maxImpactedNodes);
            }
        } catch (IOException | DependencyResolutionRequiredException failure) {
            throw new MojoExecutionException("Could not calculate impact", failure);
        }
    }

    private List<Path> classpath() throws DependencyResolutionRequiredException {
        return project.getCompileClasspathElements().stream().map(Path::of).map(Path::toAbsolutePath).filter(Files::exists).toList();
    }

    private static String format(ImpactReport report) {
        StringBuilder text = new StringBuilder("IMPACT: ").append(report.subject().id()).append('\n');
        text.append("direct=").append(report.direct().size()).append(" transitive=").append(report.transitive().size()).append('\n');
        report.direct().forEach(path -> append(text, "DIRECT", path));
        report.transitive().forEach(path -> append(text, "TRANSITIVE", path));
        return text.toString();
    }

    private static void append(StringBuilder text, String category, ImpactReport.ImpactPath path) {
        var edge = path.evidence().get(path.evidence().size() - 1);
        text.append(category).append(' ').append(path.target().id()).append(" via ").append(edge.kind()).append(" at ")
                .append(edge.provenance().file()).append(':').append(edge.provenance().line()).append(" confidence=")
                .append(edge.provenance().confidence()).append('\n');
    }

    private static String sarif(ImpactReport report) {
        StringBuilder json = new StringBuilder("{\"version\":\"2.1.0\",\"$schema\":\"https://json.schemastore.org/sarif-2.1.0.json\",\"runs\":[{\"tool\":{\"driver\":{\"name\":\"repo-intel\"}},\"results\":[");
        List<ImpactReport.ImpactPath> paths = new java.util.ArrayList<>(report.direct());
        paths.addAll(report.transitive());
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) json.append(',');
            var path = paths.get(i);
            var edge = path.evidence().get(path.evidence().size() - 1);
            json.append("{\"ruleId\":\"repo-intel-impact\",\"level\":\"warning\",\"message\":{\"text\":\"")
                    .append(Json.quote(path.target().name() + " is impacted by " + report.subject().name())).append("\"},\"locations\":[{\"physicalLocation\":{\"artifactLocation\":{\"uri\":\"")
                    .append(Json.quote(edge.provenance().file())).append("\"},\"region\":{\"startLine\":").append(Math.max(1, edge.provenance().line())).append("}}}]}" );
        }
        return json.append("]}]}\n").toString();
    }
}

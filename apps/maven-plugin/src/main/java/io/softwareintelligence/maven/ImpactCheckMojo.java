package io.softwareintelligence.maven;

import io.softwareintelligence.analyzer.java.JavaRepositoryAnalyzer;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.GraphJsonWriter;
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
            var subjectOptional = GraphQueries.findSymbol(graph, symbol);
            if (subjectOptional.isEmpty()) throw new MojoFailureException("No symbol matched: " + symbol);
            var subject = subjectOptional.get();
            ImpactReport report = GraphQueries.impact(graph, subject, depth);
            Path directory = outputDirectory.toPath().toAbsolutePath();
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("impact-graph.json"), GraphJsonWriter.write(graph));
            Files.writeString(directory.resolve("impact-report.txt"), format(report));
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
        return project.getCompileClasspathElements().stream().map(Path::of).toList();
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
}

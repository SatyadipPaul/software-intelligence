package io.softwareintelligence.maven;

import io.softwareintelligence.analyzer.java.JavaRepositoryAnalyzer;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.GraphJsonWriter;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
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

@Mojo(name = "analyze", defaultPhase = LifecyclePhase.VERIFY, requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
public final class AnalyzeMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.directory}/repo-intel", required = true)
    private File outputDirectory;

    @Parameter(defaultValue = "false")
    private boolean includeTests;

    @Override public void execute() throws MojoExecutionException {
        try {
            List<Path> classpath = classpath();
            CodeGraph graph = new JavaRepositoryAnalyzer().analyze(project.getBasedir().toPath(), classpath, includeTests);
            Path output = outputDirectory.toPath().toAbsolutePath().resolve("repo-graph.json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, GraphJsonWriter.write(graph));
            getLog().info("repo-intel: wrote " + graph.nodes().size() + " nodes and " + graph.edges().size() + " edges to " + output);
        } catch (IOException | DependencyResolutionRequiredException failure) {
            throw new MojoExecutionException("Could not analyze project", failure);
        }
    }

    private List<Path> classpath() throws DependencyResolutionRequiredException {
        List<String> elements = includeTests ? project.getTestClasspathElements() : project.getCompileClasspathElements();
        return elements.stream().map(Path::of).toList();
    }
}

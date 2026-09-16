package io.softwareintelligence.embedding;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where a model's files come from: a directory an operator points at, or a jar on the classpath.
 *
 * <p>The second is what makes a packaged model worth packaging. A consumer who adds the weights
 * artifact to their dependencies gets dense retrieval with no flag, no path and no download step,
 * and a consumer who does not is unaffected — which is the whole shape of an optional capability.
 */
public interface ModelSource {

    /** Opens one of the model's files, or null when this source does not have it. */
    InputStream open(String name) throws IOException;

    /** Something to name this source in an error, so a failure says where it looked. */
    String describe();

    /** Files in a directory. */
    static ModelSource ofDirectory(Path directory) {
        return new ModelSource() {
            @Override public InputStream open(String name) throws IOException {
                Path file = directory.resolve(name);
                return Files.isRegularFile(file) ? Files.newInputStream(file) : null;
            }

            @Override public String describe() { return directory.toAbsolutePath().toString(); }
        };
    }

    /**
     * Files packaged under {@value #CLASSPATH_ROOT} in a jar.
     *
     * <p>A fixed location rather than a configurable one: the point is that adding a dependency is
     * the whole of the configuration.
     */
    static ModelSource ofClasspath(ClassLoader loader) {
        return new ModelSource() {
            @Override public InputStream open(String name) {
                return loader.getResourceAsStream(CLASSPATH_ROOT + name);
            }

            @Override public String describe() { return "the classpath, under " + CLASSPATH_ROOT; }
        };
    }

    /** Where a packaged model's files live inside its jar. */
    String CLASSPATH_ROOT = "repo-intel/embedding/";
}

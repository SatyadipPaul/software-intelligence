package io.softwareintelligence.cli;

import picocli.CommandLine;

import java.nio.file.Path;

/**
 * What a command was pointed at: a repository (or graph file), and for most commands a subject —
 * a question, a symbol, a file.
 *
 * <p>Both of these used to be required positionals in a fixed order, so every invocation named the
 * directory the caller was already standing in:
 *
 * <pre>
 *   repo-intel impact . PaymentService
 *   repo-intel ask . "where are payments authorized?"
 * </pre>
 *
 * <p>The short forms below drop that, and the long forms still mean exactly what they did. The rule
 * is positional count, never a guess about what an argument looks like: two positionals are
 * {@code <repository> <subject>} as before, and one is the subject, with the repository coming from
 * {@code --repo} or defaulting to the current directory. Inspecting the filesystem to decide —
 * "does an entry by this name exist?" — would make {@code impact PaymentService} mean different
 * things in different checkouts, which is worse than typing a dot.
 */
record Target(Path repository, String subject) {

    /** Where a command looks when nothing on the command line says otherwise. */
    static final Path HERE = Path.of(".");

    /**
     * Resolves a command that needs both a repository and a subject.
     *
     * @param first    the first positional, which is the repository when {@code second} is present
     *                 and the subject when it is not
     * @param second   the second positional, or null when only one was given
     * @param option   the value of {@code --repo}, or null
     * @param subject  what to call the missing subject in the error message
     * @param command  the command being run, so the error carries its usage rather than the root's
     */
    static Target of(String first, String second, Path option, String subject, Object command) {
        if (first == null) {
            throw new CommandLine.ParameterException(new CommandLine(command),
                    "Missing " + subject + ". Give it alone to work on the current directory, or as "
                            + "`<repository> <" + subject + ">`.");
        }
        if (second != null) return new Target(Path.of(first), second);
        return new Target(option == null ? HERE : option, first);
    }

    /** Resolves a command whose only positional is the repository, and which may omit it. */
    static Path repository(String positional, Path option) {
        if (positional != null) return Path.of(positional);
        return option == null ? HERE : option;
    }
}

package io.softwareintelligence.cli;

import picocli.CommandLine;

@CommandLine.Command(name = "repo-intel", mixinStandardHelpOptions = true, versionProvider = Main.Version.class, subcommands = {InspectCommand.class, ImpactCommand.class, ContextCommand.class, ArchitectureCommand.class,
                AskCommand.class, IndexCommand.class, NavigateCommand.class, ServeCommand.class, SnapshotCommand.class, DiffCommand.class, EvaluateCommand.class, EnrichmentPlanCommand.class, VisualizeCommand.class, QuantizeCommand.class,
                EnrichTargetsCommand.class, EnrichApplyCommand.class},
        description = "Build deterministic, evidence-bearing repository models.",
        // Fifteen commands with no shape to them leaves a first-time reader guessing which one
        // answers their question. Two of them exist to develop this tool rather than to use it,
        // and are hidden above rather than removed - so the footer says they are there.
        footer = {
                "",
                "Commands run in the current directory unless a repository is named:",
                "  repo-intel ask \"where are payments authorized?\"",
                "  repo-intel ask /path/to/repo \"...\"      or  -C /path/to/repo",
                "",
                "Start with `ask`. Use `repo-intel <command> -h` for a command's own options.",
                "Also present, for working on this tool itself: evaluate, quantize-model."})
public final class Main implements Runnable {
    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (OutOfMemoryError exhausted) {
            // Not an Exception, so the handler below never sees it. Analyzing a large repository is
            // the normal case for this tool, and "OutOfMemoryError" alone tells the reader nothing
            // about the one-flag fix.
            System.err.println("error: ran out of heap while analyzing. Give the JVM more memory, for example:");
            System.err.println("  java -Xmx4g -jar repo-intel.jar ...");
            System.err.println("Narrowing the work also helps: --no-tests, or --no-architecture.");
            System.exit(CommandLine.ExitCode.SOFTWARE);
        }
    }

    private static int run(String[] args) {
        return new CommandLine(new Main())
                // A wrong path or a truncated file is a user error, not a crash. Printing a Java
                // stack trace for one tells the reader nothing they can act on and buries the
                // sentence that would have.
                .setExecutionExceptionHandler((exception, command, parseResult) -> {
                    System.err.println("error: " + message(exception));
                    if (System.getenv("REPO_INTEL_STACKTRACE") != null) exception.printStackTrace(System.err);
                    else System.err.println("(set REPO_INTEL_STACKTRACE=1 for the stack trace)");
                    return CommandLine.ExitCode.SOFTWARE;
                })
                .execute(args);
    }

    private static String message(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    @Override public void run() { new CommandLine(this).usage(System.out); }

    /** The version the jar was built as, read from its manifest; `-V` printed nothing before this. */
    static final class Version implements CommandLine.IVersionProvider {
        static String current() {
            String version = Main.class.getPackage().getImplementationVersion();
            return version == null ? "development build" : version;
        }

        @Override public String[] getVersion() {
            return new String[] {"repo-intel " + current()};
        }
    }
}

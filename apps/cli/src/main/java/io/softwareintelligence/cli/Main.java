package io.softwareintelligence.cli;

import picocli.CommandLine;

@CommandLine.Command(name = "repo-intel", mixinStandardHelpOptions = true, subcommands = {InspectCommand.class, ImpactCommand.class, ContextCommand.class, ArchitectureCommand.class,
                AskCommand.class, IndexCommand.class, NavigateCommand.class, SnapshotCommand.class, DiffCommand.class, EvaluateCommand.class, EnrichmentPlanCommand.class, VisualizeCommand.class,
                EnrichTargetsCommand.class, EnrichApplyCommand.class},
        description = "Build deterministic, evidence-bearing repository models.")
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
}

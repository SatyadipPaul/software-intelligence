package io.softwareintelligence.cli;

import picocli.CommandLine;

@CommandLine.Command(name = "repo-intel", mixinStandardHelpOptions = true, subcommands = {InspectCommand.class, ImpactCommand.class, ContextCommand.class, ArchitectureCommand.class,
                AskCommand.class, SnapshotCommand.class, DiffCommand.class, EvaluateCommand.class, EnrichmentPlanCommand.class, VisualizeCommand.class,
                EnrichTargetsCommand.class, EnrichApplyCommand.class},
        description = "Build deterministic, evidence-bearing repository models.")
public final class Main implements Runnable {
    public static void main(String[] args) {
        System.exit(new CommandLine(new Main())
                // A wrong path or a truncated file is a user error, not a crash. Printing a Java
                // stack trace for one tells the reader nothing they can act on and buries the
                // sentence that would have.
                .setExecutionExceptionHandler((exception, command, parseResult) -> {
                    System.err.println("error: " + message(exception));
                    if (System.getenv("REPO_INTEL_STACKTRACE") != null) exception.printStackTrace(System.err);
                    else System.err.println("(set REPO_INTEL_STACKTRACE=1 for the stack trace)");
                    return CommandLine.ExitCode.SOFTWARE;
                })
                .execute(args));
    }

    private static String message(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    @Override public void run() { new CommandLine(this).usage(System.out); }
}

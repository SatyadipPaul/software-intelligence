package io.softwareintelligence.cli;

import picocli.CommandLine;

@CommandLine.Command(name = "repo-intel", mixinStandardHelpOptions = true, subcommands = {InspectCommand.class, ImpactCommand.class, ContextCommand.class, ArchitectureCommand.class,
                AskCommand.class, SnapshotCommand.class, DiffCommand.class, EvaluateCommand.class, EnrichmentPlanCommand.class},
        description = "Build deterministic, evidence-bearing repository models.")
public final class Main implements Runnable {
    public static void main(String[] args) { System.exit(new CommandLine(new Main()).execute(args)); }
    @Override public void run() { new CommandLine(this).usage(System.out); }
}

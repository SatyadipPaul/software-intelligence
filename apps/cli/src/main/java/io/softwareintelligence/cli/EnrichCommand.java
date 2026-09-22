package io.softwareintelligence.cli;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.GraphJsonWriter;
import io.softwareintelligence.model.Json;
import io.softwareintelligence.queryengine.EnrichmentClaims;
import io.softwareintelligence.queryengine.EnrichmentMerge;
import io.softwareintelligence.queryengine.BranchEnrichment;
import io.softwareintelligence.queryengine.EnrichmentPlanner;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(mixinStandardHelpOptions = true, name = "enrich-targets",
        description = "Write work packets for a semantic enricher: ranked symbols, their evidence, and the relationships each may cite.")
final class EnrichTargetsCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "REPOSITORY",
            description = "Java repository or graph file. Omit it to use the current directory.")
    private String sourceArgument;

    /** Resolved once in call(): the positional, --repo, or the current directory. */
    private Path source;
        @CommandLine.Option(names = {"-o", "--output"}, defaultValue = "enrichment-targets.json", description = "Work packet file")
    private Path output;
    @CommandLine.Option(names = "--max-tokens", defaultValue = "20000", description = "Token budget for the whole run") private int maxTokens;
    @CommandLine.Option(names = "--cost-per-1k", defaultValue = "0.003", description = "Cost per thousand tokens, for the audit line") private double costPerThousand;
    @CommandLine.Option(names = "--limit", defaultValue = "40", description = "Most packets to emit") private int limit;
    @CommandLine.Option(names = "--format", defaultValue = "JSON",
            description = "JSON for a program that loops; MARKDOWN for a person or a chat assistant")
    private Format format;

    @CommandLine.Option(names = "--branches",
            description = "Rank index-tree branches instead of symbols: a summary there is read by every descent through it")
    private boolean branches;

    @CommandLine.Option(names = "--index", description = "Use a pinned tree file rather than deriving one")
    private Path indexFile;

    enum Format { JSON, MARKDOWN }
    @CommandLine.Mixin private AnalysisOptions options;

    @Override public Integer call() throws Exception {
        source = options.repository(sourceArgument);
        CodeGraph graph = options.analyze(source);
        EnrichmentPlanner.Budget budget = new EnrichmentPlanner.Budget(maxTokens, costPerThousand);
        EnrichmentPlanner.Plan plan = branches
                ? BranchEnrichment.plan(graph, TreeOptions.load(graph, indexFile), budget)
                : EnrichmentPlanner.plan(graph, budget);
        List<EnrichmentPlanner.Candidate> selected = plan.selected().stream().limit(limit).toList();
        List<EnrichmentClaims.WorkPacket> packets = EnrichmentClaims.workPackets(graph, selected);
        Path destination = output.toAbsolutePath();
        if (destination.getParent() != null) Files.createDirectories(destination.getParent());

        if (format == Format.MARKDOWN) {
            Files.writeString(destination, EnrichmentClaims.markdown(packets, source.getFileName().toString()));
            System.out.printf("Wrote %d work packets to %s%n", packets.size(), destination);
            System.out.print(EnrichmentPlanner.audit(plan, budget));
            return 0;
        }

        StringBuilder json = new StringBuilder("{\n  \"briefing\": \"")
                .append(Json.quote(EnrichmentClaims.briefing())).append("\",\n  \"packets\": [");
        for (int i = 0; i < packets.size(); i++) {
            EnrichmentClaims.WorkPacket packet = packets.get(i);
            if (i > 0) json.append(',');
            json.append("\n    {\"subject\": \"").append(Json.quote(packet.subject()))
                    .append("\", \"kind\": \"").append(Json.quote(packet.kind()))
                    .append("\", \"name\": \"").append(Json.quote(packet.name()))
                    .append("\", \"facts\": {");
            int f = 0;
            for (var entry : packet.facts().entrySet()) {
                if (f++ > 0) json.append(", ");
                json.append('"').append(Json.quote(entry.getKey())).append("\": \"").append(Json.quote(entry.getValue())).append('"');
            }
            json.append("}, \"evidence\": [");
            for (int e = 0; e < packet.evidence().size(); e++) {
                if (e > 0) json.append(", ");
                json.append('"').append(Json.quote(packet.evidence().get(e))).append('"');
            }
            json.append("], \"citable\": [");
            for (int c = 0; c < packet.citableRelationships().size(); c++) {
                if (c > 0) json.append(", ");
                json.append('"').append(Json.quote(packet.citableRelationships().get(c))).append('"');
            }
            json.append("]}");
        }
        json.append("\n  ]\n}\n");
        Files.writeString(destination, json.toString());
        System.out.printf("Wrote %d work packets to %s%n", packets.size(), destination);
        System.out.print(EnrichmentPlanner.audit(plan, budget));
        return 0;
    }
}

@CommandLine.Command(mixinStandardHelpOptions = true, name = "enrich-apply",
        description = "Verify a claims file against the graph and apply only what the evidence supports.")
final class EnrichApplyCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "REPOSITORY",
            description = "Java repository or graph file. Omit it to use the current directory.")
    private String first;

    @CommandLine.Parameters(index = "1", arity = "0..1", paramLabel = "CLAIMS",
            description = "Claims file written by an enricher")
    private String second;

    /** Both resolved in call() from the positionals above, in either accepted order. */
    private Path source;
    private Path claims;
    @CommandLine.Option(names = {"-o", "--output"}, description = "Enriched graph JSON") private Path output;
    @CommandLine.Option(names = "--strict", description = "Exit non-zero if any claim is rejected or disputed") private boolean strict;
    @CommandLine.Mixin private AnalysisOptions options;

    @Override public Integer call() throws Exception {
        Target target = options.target(first, second, "claims file", this);
        source = target.repository();
        claims = Path.of(target.subject());
        CodeGraph graph = options.analyze(source);
        List<EnrichmentClaims.Claim> parsed = EnrichmentClaims.parse(Files.readString(claims));
        EnrichmentMerge.Result result = EnrichmentMerge.apply(graph, parsed);
        System.out.printf("Parsed %d claims from %s%n", parsed.size(), claims);
        System.out.print(EnrichmentMerge.report(result));
        if (output != null) {
            Path destination = output.toAbsolutePath();
            if (destination.getParent() != null) Files.createDirectories(destination.getParent());
            Files.writeString(destination, GraphJsonWriter.write(graph));
            System.out.printf("Wrote enriched graph to %s%n", destination);
        }
        return strict && !result.clean() ? 1 : 0;
    }
}

package io.softwareintelligence.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs every command against the fixture, in every accepted argument form, and requires each to
 * succeed.
 *
 * <p>Unit tests cover the pieces; nothing covered the commands as wired. When the positional
 * arguments became optional, {@code diff} started reading its snapshot before resolving which
 * positional named it, and threw a NullPointerException on every call - through a full
 * {@code mvn verify}, because no test ever ran {@code diff}. It was caught by a CI step instead,
 * after it had shipped to the branch. This is that CI step's coverage, moved to where it fails
 * first.
 */
final class CommandSmokeTest {

    private static final Path FIXTURE = Path.of("../../fixtures/sample-commerce").toAbsolutePath().normalize();
    private static final Path QUESTIONS = Path.of("../../evaluation/sample-commerce.questions.tsv").toAbsolutePath().normalize();

    private record Run(int exit, String output) { }

    private static Run run(String... args) {
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
        try {
            int exit = new CommandLine(new Main())
                    .setExecutionExceptionHandler((exception, command, parse) -> {
                        exception.printStackTrace(capture);
                        return 1;
                    })
                    .execute(args);
            return new Run(exit, buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private static void succeeds(List<String> failures, String... args) {
        Run run = run(args);
        if (run.exit() != 0) {
            failures.add(String.join(" ", args) + "\n    exit " + run.exit() + ": "
                    + run.output().lines().limit(6).reduce("", (a, b) -> a + "\n      " + b));
        }
    }

    @Test void every_command_runs_in_every_argument_form(@TempDir Path out) throws IOException {
        String repo = FIXTURE.toString();
        String snapshot = out.resolve("baseline.snapshot.json").toString();
        Path claims = Files.writeString(out.resolve("claims.json"), "{\"claims\":[]}");
        List<String> failures = new ArrayList<>();

        // Single-positional commands: positional, flag.
        for (String command : List.of("inspect", "architecture", "index", "enrichment-plan")) {
            String output = out.resolve(command + ".out").toString();
            List<String> extra = command.equals("inspect") ? List.of("-o", output) : List.of();
            succeeds(failures, concat(List.of(command, repo), extra));
            succeeds(failures, concat(List.of(command, "-C", repo), extra));
        }
        succeeds(failures, "visualize", repo, "-o", out.resolve("v1.html").toString());
        succeeds(failures, "visualize", "-C", repo, "-o", out.resolve("v2.html").toString());
        succeeds(failures, "enrich-targets", repo, "-o", out.resolve("t1.json").toString());
        succeeds(failures, "enrich-targets", "-C", repo, "-o", out.resolve("t2.json").toString());
        succeeds(failures, "snapshot", repo, "-o", snapshot);
        succeeds(failures, "snapshot", "-C", repo, "-o", out.resolve("s2.json").toString());

        // Two-positional commands: both positionals, subject alone with the flag.
        succeeds(failures, "impact", repo, "PaymentService", "-d", "1");
        succeeds(failures, "impact", "PaymentService", "-C", repo, "-d", "1");
        succeeds(failures, "context", repo, "PaymentService", "-o", out.resolve("c1.json").toString());
        succeeds(failures, "context", "PaymentService", "-C", repo, "-o", out.resolve("c2.json").toString());
        succeeds(failures, "ask", repo, "how are payments authorized?");
        succeeds(failures, "ask", "how are payments authorized?", "-C", repo);
        succeeds(failures, "diff", repo, snapshot);
        succeeds(failures, "diff", snapshot, "-C", repo);
        succeeds(failures, "evaluate", repo, QUESTIONS.toString(), "--fail-under", "0");
        succeeds(failures, "evaluate", QUESTIONS.toString(), "-C", repo, "--fail-under", "0");
        succeeds(failures, "enrich-apply", repo, claims.toString(), "-o", out.resolve("e1.json").toString());
        succeeds(failures, "enrich-apply", claims.toString(), "-C", repo, "-o", out.resolve("e2.json").toString());

        // navigate decides by flag rather than count: start with a question, continue without one.
        String session = out.resolve("nav.json").toString();
        succeeds(failures, "navigate", repo, "where are payments authorized?", "--session", session);
        String session2 = out.resolve("nav2.json").toString();
        succeeds(failures, "navigate", "where are payments authorized?", "-C", repo, "--session", session2);

        assertTrue(failures.isEmpty(), failures.size() + " invocation(s) failed:\n  " + String.join("\n  ", failures));
    }

    private static String[] concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all.toArray(String[]::new);
    }
}

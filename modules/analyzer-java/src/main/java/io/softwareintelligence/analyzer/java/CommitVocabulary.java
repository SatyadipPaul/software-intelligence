package io.softwareintelligence.analyzer.java;

import io.softwareintelligence.model.Attributes;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Reads the repository's own commit subjects as prose about the files they changed.
 *
 * <p>Commit messages are the one body of human language in a repository that is written in the
 * asker's register, tied to specific files, and present whether or not anyone wrote a Javadoc.
 * "Repository-level Code Search with Neural Retrieval Methods" (arXiv 2502.07067) retrieves over
 * them and reports large gains against a BM25 baseline. This project indexed none of them.
 *
 * <h2>The guard, and the decision it asked for</h2>
 *
 * <p>This is the first text the analyzer reads that is <em>not the source at the pinned commit</em>,
 * which is the invariant every other fact in the graph rests on. Three consequences were decided
 * deliberately rather than discovered later:
 *
 * <ul>
 *   <li><b>It lives in the graph, under a key that names its provenance.</b> A side index would need
 *       its own lifecycle, its own file format and its own staleness rules, and every consumer would
 *       have to learn about it. The attribute is called {@code history} so that anything rendering a
 *       card shows where the claim came from, and the repository node records the commit it was read
 *       at, so a graph carrying history says which history.</li>
 *   <li><b>A shallow clone is refused, not tolerated.</b> Two shallow clones of the same commit can
 *       hold different history, which would make this the only part of the graph that is not a
 *       function of the commit. Refusing is the only answer consistent with the rest.</li>
 *   <li><b>It is off by default.</b> Reading history costs a process and needs a repository that
 *       still has one; analysis of an exported tree, or of a graph file, must keep working.</li>
 * </ul>
 *
 * <h2>What a commit has to look like to count</h2>
 *
 * <p>Most commit subjects describe maintenance rather than meaning. The filter that does the work is
 * not a list of bot patterns but <b>breadth</b>: a commit touching more than a handful of files is a
 * sweep — a copyright year, a formatter, a licence header — and says nothing about any one of them.
 * spring-petclinic's widest commit touches 1082 files; its median touches one.
 */
public final class CommitVocabulary {

    /**
     * Above this many changed files, a commit is a sweep rather than a description.
     *
     * <p>Measured on spring-petclinic: with no limit, {@code PetValidator} is described as "Updated
     * Copyright to year 2025; Apply spring-format plugin; Fix Apache license headers". At ten, it is
     * described as "chaining validation so we can see multiple error messages". The number follows
     * the shape of the data rather than being tuned — a focused change touches a file and its test.
     */
    static final int SWEEP = 10;

    /**
     * How many of a file's commits are read, most recent first.
     *
     * <p>This is where recency enters. An old subject describes code that may have been rewritten
     * twice since; a budget spent on the newest twenty is a budget spent on the file as it is.
     */
    static final int RECENT = 20;

    /** Subjects are one line each; a runaway one is a pasted diff, not a description. */
    private static final int LONGEST_SUBJECT = 120;

    /** Separates one commit from the next in the log output; a subject cannot contain it. */
    private static final char END_OF_COMMIT = 1;

    /** Separates a commit's hash from its subject; neither a path nor a subject can contain it. */
    private static final char END_OF_FIELD = 0;

    /** Conventional-commit and tracker prefixes: {@code fix(owner):}, {@code GH-42}, {@code #17}. */
    private static final Pattern PREFIX = Pattern.compile("^\\s*\\w+(\\([^)]*\\))?\\s*:\\s*");

    /** A leading tool tag, as release and CI plugins write it: {@code [maven-release-plugin]}. */
    private static final Pattern TOOL_TAG = Pattern.compile("^\\s*\\[[^\\]]{0,60}\\]\\s*");
    private static final Pattern TICKET = Pattern.compile("(?i)#\\d+|\\bgh-\\d+\\b|\\bissue\\s+\\d+");
    private static final Pattern URL = Pattern.compile("(?i)\\bhttps?://\\S+");

    /**
     * Verbs nearly every commit subject starts with, which therefore separate no two of them.
     *
     * <p>The same idea as dropping {@code should} from a test name: "Fix the pagination" and "Add
     * the pagination" are both about pagination, and a term index that sees {@code fix} on a third
     * of the repository's cards learns nothing from it.
     */
    private static final Set<String> LEADING_NOISE = Set.of(
            "fix", "fixes", "fixed", "fixing", "add", "adds", "added", "adding", "remove", "removes",
            "removed", "removing", "update", "updates", "updated", "updating", "refactor",
            "refactored", "polish", "polished", "cleanup", "clean", "make", "makes", "use", "using",
            "improve", "improved", "change", "changed", "changes", "introduce", "introduced",
            "apply", "applied", "implement", "implements", "implemented", "allow", "allows",
            "support", "minor", "small", "wip", "chore", "revert", "reverts");

    private CommitVocabulary() { }

    /** One commit's subject and the paths it touched. */
    record Commit(String subject, List<String> files) { }

    /**
     * Writes a bounded digest of recent focused commit subjects onto the files and types they
     * changed, and records on the repository node which history it read.
     *
     * @throws IllegalArgumentException when the directory is not a git repository, or is shallow
     */
    public static void attach(CodeGraph graph, Path repository) {
        Path marker = repository.resolve(".git");
        if (!Files.isDirectory(marker) && !Files.isRegularFile(marker)) {
            throw new IllegalArgumentException("commit vocabulary was asked for, but " + repository
                    + " is not a git repository. Analysis of an exported tree cannot read history.");
        }
        if (Boolean.parseBoolean(git(repository, "rev-parse", "--is-shallow-repository").trim())) {
            throw new IllegalArgumentException("commit vocabulary was asked for, but " + repository
                    + " is a shallow clone. Two shallow clones of one commit can hold different "
                    + "history, and this would then be the only part of the graph that is not a "
                    + "function of the commit. Fetch the full history, or leave the pass off.");
        }
        String head = git(repository, "rev-parse", "HEAD").trim();

        Map<String, List<String>> byPath = new TreeMap<>();
        int focused = 0;
        for (Commit commit : log(repository)) {
            if (commit.files().size() > SWEEP) continue;
            String phrase = phrase(commit.subject());
            if (phrase.isEmpty()) continue;
            focused++;
            for (String file : commit.files()) {
                List<String> subjects = byPath.computeIfAbsent(file, ignored -> new ArrayList<>());
                // Newest first, because the log is; anything past the cap describes code that has
                // very likely been rewritten since.
                if (subjects.size() < RECENT) subjects.add(phrase);
            }
        }
        if (byPath.isEmpty()) return;

        Map<String, String> digests = new TreeMap<>();
        for (Map.Entry<String, List<String>> entry : byPath.entrySet()) {
            String digest = VocabularyDigest.of(entry.getValue());
            if (!digest.isEmpty()) digests.put(entry.getKey(), digest);
        }

        int written = 0;
        for (GraphNode node : List.copyOf(graph.nodes())) {
            if (!describable(node.kind())) continue;
            String digest = digests.get(normalize(node.provenance().file()));
            if (digest == null || describedAlready(node)) continue;
            graph.upsertNode(with(node, Attributes.HISTORY, digest), true);
            written++;
        }
        record(graph, head, focused, written);
    }

    /**
     * Files and the declarations they hold.
     *
     * <p>A commit changes a file, so a file is what the subject is literally about; the declarations
     * in it get the same digest because a type is what a question is usually about and what an
     * anchor resolves to. Methods do not: a subject describes a change to a file, and pushing it
     * down to every member of every type in that file would repeat one sentence a hundred times.
     */
    private static boolean describable(EntityKind kind) {
        return switch (kind) {
            case FILE, TYPE, INTERFACE, CONTROLLER, SERVICE, REPOSITORY_COMPONENT, ENTITY,
                 CONFIGURATION -> true;
            default -> false;
        };
    }

    /**
     * Only where there is nothing better.
     *
     * <p>The test-name experiment measured this both ways and the result was unambiguous: adding
     * prose beside an existing doc sentence cost more than adding it where there was none, because a
     * second description dilutes the one that already answered the question. What was learned there
     * is applied here rather than re-learned.
     */
    private static boolean describedAlready(GraphNode node) {
        return !node.attributes().getOrDefault(Attributes.DOC, "").isBlank()
                || !node.attributes().getOrDefault(Attributes.BEHAVIOUR, "").isBlank();
    }

    /** Puts the commit this history came from on the record, so a graph says which history it holds. */
    private static void record(CodeGraph graph, String head, int focused, int written) {
        for (GraphNode node : List.copyOf(graph.nodes())) {
            if (node.kind() != EntityKind.REPOSITORY) continue;
            GraphNode stamped = with(node, "history.commit", head);
            stamped = with(stamped, "history.focusedCommits", Integer.toString(focused));
            stamped = with(stamped, "history.describedSymbols", Integer.toString(written));
            stamped = with(stamped, "history.sweepThreshold", Integer.toString(SWEEP));
            graph.upsertNode(stamped, true);
        }
    }

    private static GraphNode with(GraphNode node, String key, String value) {
        Map<String, String> attributes = new LinkedHashMap<>(node.attributes());
        attributes.put(key, value);
        return new GraphNode(node.id(), node.kind(), node.name(), Map.copyOf(attributes), node.provenance());
    }

    /**
     * A commit subject as the sentence it was written to be.
     *
     * <p>Ticket numbers and URLs go for the reason issue numbers go from a test name: they are the
     * rarest tokens in the repository and the least meaningful to anyone asking a question.
     */
    static String phrase(String subject) {
        String text = URL.matcher(subject).replaceAll(" ");
        text = TICKET.matcher(text).replaceAll(" ");
        text = TOOL_TAG.matcher(text).replaceFirst("");
        text = PREFIX.matcher(text).replaceFirst("");
        text = text.replaceAll("[^A-Za-z0-9]+", " ");
        List<String> words = new ArrayList<>();
        for (String word : text.split(" ")) {
            String letters = word.replaceAll("[0-9]+", "");
            if (letters.length() > 1) words.add(letters.toLowerCase(Locale.ROOT));
        }
        int start = 0;
        while (start < words.size() && LEADING_NOISE.contains(words.get(start))) start++;
        StringBuilder phrase = new StringBuilder();
        for (String word : words.subList(start, words.size())) {
            if (phrase.length() + word.length() + 1 > LONGEST_SUBJECT) break;
            if (!phrase.isEmpty()) phrase.append(' ');
            phrase.append(word);
        }
        return phrase.toString();
    }

    /**
     * Every non-merge commit reachable from HEAD, newest first, with the paths it touched.
     *
     * <p>Merges are excluded because their subject describes an integration rather than a change,
     * and their file list is the union of everything merged — the widest sweep in the log.
     */
    static List<Commit> log(Path repository) {
        String format = "--format=%x01%H%x00%s";
        String output = git(repository, "log", "--no-merges", format, "--name-only", "HEAD");
        List<Commit> commits = new ArrayList<>();
        for (String block : output.split(String.valueOf(END_OF_COMMIT))) {
            if (block.isBlank()) continue;
            int newline = block.indexOf('\n');
            String header = newline < 0 ? block : block.substring(0, newline);
            int field = header.indexOf(END_OF_FIELD);
            if (field < 0) continue;
            String subject = header.substring(field + 1);
            List<String> files = new ArrayList<>();
            if (newline >= 0) {
                for (String line : block.substring(newline + 1).split("\n")) {
                    if (!line.isBlank()) files.add(line.trim());
                }
            }
            if (!files.isEmpty()) commits.add(new Commit(subject, List.copyOf(files)));
        }
        return commits;
    }

    /** Paths from git are repository-relative with forward slashes; so is a node's provenance. */
    private static String normalize(String file) {
        return file == null ? "" : file.replace('\\', '/');
    }

    private static String git(Path repository, String... arguments) {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.quotepath=false", "-C",
                repository.toAbsolutePath().toString()));
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("git " + String.join(" ", arguments) + " did not finish");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("git " + String.join(" ", arguments)
                        + " failed with exit code " + process.exitValue()
                        + ". Is git on the PATH, and is " + repository + " a repository it can read?");
            }
            return output;
        } catch (IOException cause) {
            throw new UncheckedIOException("could not run git in " + repository, cause);
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while reading git history", cause);
        }
    }
}

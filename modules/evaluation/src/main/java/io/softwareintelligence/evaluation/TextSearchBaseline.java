package io.softwareintelligence.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What a developer would get from grep, scored on the same questions by the same rules.
 *
 * <p>Without this, "structural accuracy 1.000" is unfalsifiable praise: the number says nothing
 * about whether building a graph was worth it. The only claim that matters is <em>better than
 * searching for the name</em>, and the only way to make that claim is to measure the alternative.
 *
 * <p>Deliberately naive, because that is the honest comparison: find every file whose text contains
 * the subject's simple name, and treat the types those files declare as the answer. That is roughly
 * what a search-based RAG retriever returns before any reranking.
 */
public final class TextSearchBaseline {

    public record Answer(Set<String> symbols, Set<String> locations, int filesMatched) { }

    private TextSearchBaseline() { }

    public static Answer search(Path repository, String subject) throws IOException {
        String needle = simpleName(subject);
        Set<String> symbols = new LinkedHashSet<>();
        Set<String> locations = new LinkedHashSet<>();
        int matched = 0;
        List<Path> sources;
        try (Stream<Path> files = Files.walk(repository)) {
            sources = files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
        for (Path source : sources) {
            String text;
            try {
                text = Files.readString(source, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException unreadable) {
                continue;
            }
            if (!text.contains(needle)) continue;
            matched++;
            String relative = repository.relativize(source).toString().replace('\\', '/');
            // Every type the matching file declares becomes part of the answer, and the line of the
            // first mention becomes its evidence - the closest a text search gets to provenance.
            symbols.addAll(declaredTypes(text));
            int line = lineOf(text, needle);
            if (line > 0) locations.add(relative + ":" + line);
        }
        return new Answer(symbols, locations, matched);
    }

    /** Scores a baseline answer with the same recall rule the graph is scored by. */
    public static double recall(Answer answer, GroundedQuestion question) {
        if (question.expectedNames().isEmpty()) return 1.0;
        long hits = question.expectedNames().stream()
                .filter(expected -> answer.symbols().stream().anyMatch(symbol -> EvaluationHarness.matches(symbol, expected)))
                .count();
        return (double) hits / question.expectedNames().size();
    }

    public static double evidenceRecall(Answer answer, GroundedQuestion question) {
        if (question.expectedLocations().isEmpty()) return 1.0;
        long hits = question.expectedLocations().stream()
                .filter(expected -> answer.locations().stream().anyMatch(location -> location.endsWith(expected)))
                .count();
        return (double) hits / question.expectedLocations().size();
    }

    private static List<String> declaredTypes(String text) {
        List<String> types = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^\\s*(?:public\\s+|final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+|static\\s+)*"
                        + "(?:class|interface|enum|record|@interface)\\s+([A-Za-z_$][A-Za-z0-9_$]*)")
                .matcher(text);
        while (matcher.find()) types.add(matcher.group(1));
        return types;
    }

    private static int lineOf(String text, String needle) {
        int index = text.indexOf(needle);
        if (index < 0) return 0;
        int line = 1;
        for (int i = 0; i < index; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    private static String simpleName(String subject) {
        String name = subject;
        int hash = name.indexOf('#');
        if (hash > 0) name = name.substring(0, hash);
        int colon = name.lastIndexOf(':');
        if (colon >= 0) name = name.substring(colon + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }
}

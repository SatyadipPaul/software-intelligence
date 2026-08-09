package io.softwareintelligence.evaluation;

import java.util.List;

/**
 * One grounded question and the answer the repository itself proves.
 *
 * <p>Expectations are written against <em>source locations and source names</em>, never against
 * graph ids. Ids change whenever symbol identity improves — that is a healthy change — and a
 * question set keyed to them would need rewriting on every semantic increment. A question keyed to
 * {@code VetController.java:62} stays valid across all of them.
 */
public record GroundedQuestion(
        String id,
        String repository,
        String question,
        Kind kind,
        String subject,
        List<String> expectedNames,
        List<String> expectedLocations,
        double minimumConfidence) {

    public enum Kind { IMPACT, ENDPOINT, PERSISTENCE, STRUCTURE, LOOKUP }

    /** Tab-separated line format, so a question set is reviewable in a diff. */
    public static GroundedQuestion parse(String line) {
        String[] fields = line.split("\t", -1);
        if (fields.length < 8) throw new IllegalArgumentException("expected 8 tab-separated fields, got " + fields.length + ": " + line);
        return new GroundedQuestion(fields[0].trim(), fields[1].trim(), fields[2].trim(),
                Kind.valueOf(fields[3].trim().toUpperCase(java.util.Locale.ROOT)), fields[4].trim(),
                split(fields[5]), split(fields[6]), Double.parseDouble(fields[7].trim()));
    }

    private static List<String> split(String field) {
        if (field == null || field.isBlank()) return List.of();
        return java.util.Arrays.stream(field.split("\\|")).map(String::trim).filter(value -> !value.isBlank()).toList();
    }
}

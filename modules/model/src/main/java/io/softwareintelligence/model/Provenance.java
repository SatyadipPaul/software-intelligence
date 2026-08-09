package io.softwareintelligence.model;

/** Explains why an entity or relationship exists in the repository model. */
public record Provenance(String resolver, double confidence, String file, int line, int column) {
    public static Provenance syntax(String file, int line, int column) {
        return new Provenance("JDT_AST", 1.0, file, line, column);
    }

    public static Provenance unresolved(String file, int line, int column) {
        return new Provenance("JDT_AST_UNRESOLVED", 0.55, file, line, column);
    }
}


package io.softwareintelligence.framework.spring;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the table or entity names a JPQL or SQL statement names directly.
 *
 * <p>This is deliberately syntactic. It reads the names that follow FROM, JOIN, UPDATE, and INSERT
 * INTO and nothing else: no dialect parsing, no subquery flattening, no attempt to guess at dynamic
 * SQL. A name it cannot see is not reported, which keeps a query relationship as evidence-backed as
 * every other edge in the graph.
 */
public final class SqlTables {
    private static final Pattern SOURCES = Pattern.compile(
            "(?i)\\b(?:from|join|update|into)\\s+([A-Za-z_][A-Za-z0-9_$.]*)");
    private static final Set<String> KEYWORDS = Set.of("select", "where", "set", "values", "on", "as", "left", "right", "inner", "outer", "fetch");

    private SqlTables() { }

    public static Set<String> referenced(String statement) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = SOURCES.matcher(statement);
        while (matcher.find()) {
            String name = matcher.group(1);
            // A JPQL join names a path on the alias (o.pets); the owning entity is already recorded
            // through the root FROM clause, so a path is not an independent table reference.
            if (name.contains(".") && !name.contains("_")) continue;
            if (KEYWORDS.contains(name.toLowerCase(Locale.ROOT))) continue;
            names.add(name);
        }
        return names;
    }
}

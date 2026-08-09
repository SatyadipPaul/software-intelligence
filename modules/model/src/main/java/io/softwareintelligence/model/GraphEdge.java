package io.softwareintelligence.model;

import java.util.Map;

public record GraphEdge(String from, String to, RelationKind kind, Map<String, String> attributes, Provenance provenance) { }


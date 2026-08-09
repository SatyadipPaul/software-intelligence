package io.softwareintelligence.model;

import java.util.Map;

public record GraphNode(String id, EntityKind kind, String name, Map<String, String> attributes, Provenance provenance) { }


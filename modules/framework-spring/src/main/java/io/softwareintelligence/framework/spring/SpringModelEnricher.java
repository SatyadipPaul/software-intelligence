package io.softwareintelligence.framework.spring;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.Provenance;
import io.softwareintelligence.model.RelationKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Interprets the deterministic Java facts as an operational Spring model: which bean satisfies an
 * injected interface, which endpoints a guard protects, which tables a query touches, which topics
 * a producer writes, and which external services a client calls.
 *
 * <p>This layer only reads facts the analyzer already recorded with provenance. It adds claims; it
 * never rewrites or deletes deterministic evidence, and every claim it adds carries the source
 * position of the fact it was derived from.
 */
public final class SpringModelEnricher {
    public static final String BEAN_RESOLVER = "SPRING_BEAN_WIRING";
    public static final String FRAMEWORK_RESOLVER = "SPRING_FRAMEWORK_MODEL";

    private static final Set<String> COMPONENT_KINDS_ANNOTATIONS =
            Set.of("Component", "Service", "Repository", "Controller", "RestController", "Configuration", "ConfigurationProperties");
    private static final Set<String> GUARD_ANNOTATIONS = Set.of("PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed", "DenyAll", "PermitAll");
    private static final List<String> PRODUCER_METHODS = List.of("send", "sendDefault");
    private static final Set<String> REST_CLIENT_TYPES =
            Set.of("org.springframework.web.client.RestTemplate", "org.springframework.web.reactive.function.client.WebClient",
                    "org.springframework.web.client.RestClient", "java.net.http.HttpClient");

    public void enrich(CodeGraph graph) {
        Map<String, List<GraphNode>> implementations = implementationsByInterface(graph);
        Map<String, String> tablesByEntityName = tablesByEntityName(graph);
        wireBeans(graph, implementations);
        modelSecurityGuards(graph);
        modelConfigurationProperties(graph);
        modelQueries(graph, tablesByEntityName);
        modelProducers(graph);
        modelRestClients(graph);
        modelSerialization(graph);
    }

    /** Resolves an injected interface to the Spring components in this repository that implement it. */
    private void wireBeans(CodeGraph graph, Map<String, List<GraphNode>> implementations) {
        List<GraphEdge> injectionPoints = new ArrayList<>();
        for (GraphEdge edge : graph.edges()) {
            if (edge.kind() == RelationKind.DEPENDS_ON && edge.attributes().containsKey("injection")) injectionPoints.add(edge);
        }
        for (GraphNode field : graph.nodes()) {
            if (field.kind() != EntityKind.FIELD) continue;
            String annotations = field.attributes().getOrDefault("annotations", "");
            if (!annotations.contains("Autowired") && !annotations.contains("Inject") && !annotations.contains("Resource")) continue;
            graph.node(declaringType(field.id())).ifPresent(owner ->
                    resolveInto(graph, owner.id(), field.attributes().getOrDefault("declaredType", ""), implementations, field.provenance(), "field"));
        }
        for (GraphEdge injection : injectionPoints) {
            String owner = injection.from().contains("#") ? injection.from().substring(0, injection.from().indexOf('#')) : injection.from();
            resolveInto(graph, owner, injection.to(), implementations, injection.provenance(), injection.attributes().get("injection"));
        }
    }

    private void resolveInto(CodeGraph graph, String consumerId, String declaredType,
                             Map<String, List<GraphNode>> implementations, Provenance origin, String style) {
        String interfaceId = declaredType.startsWith("type:") ? declaredType : resolveTypeId(graph, declaredType);
        if (interfaceId == null || interfaceId.equals(consumerId)) return;
        List<GraphNode> beans = implementations.getOrDefault(interfaceId, List.of()).stream().filter(this::isComponent).toList();
        if (beans.isEmpty()) return;
        double confidence = beans.size() == 1 ? 0.95 : 0.60;
        for (GraphNode bean : beans) {
            graph.addEdge(new GraphEdge(consumerId, bean.id(), RelationKind.DEPENDS_ON,
                    Map.of("resolution", "spring-bean", "injection", style == null ? "unknown" : style,
                            "declaredType", interfaceId, "candidates", Integer.toString(beans.size())),
                    new Provenance(BEAN_RESOLVER, confidence, origin.file(), origin.line(), origin.column())));
        }
    }

    /** A guard annotation becomes a first-class node so "what does this role protect" is answerable. */
    private void modelSecurityGuards(CodeGraph graph) {
        for (GraphNode node : List.copyOf(graph.nodes())) {
            for (String annotation : GUARD_ANNOTATIONS) {
                String key = "annotation." + annotation;
                if (!node.attributes().containsKey(key)) continue;
                String expression = node.attributes().getOrDefault(key + ".value", annotation);
                String guardId = "guard:" + annotation + ":" + expression;
                graph.upsertNode(new GraphNode(guardId, EntityKind.SECURITY_GUARD, annotation + " " + expression,
                        Map.of("annotation", annotation, "expression", expression), node.provenance()), true);
                graph.addEdge(new GraphEdge(guardId, node.id(), RelationKind.CONFIGURES,
                        Map.of("resolution", "spring-security"), framework(node.provenance())));
            }
        }
    }

    private void modelConfigurationProperties(CodeGraph graph) {
        for (GraphNode node : List.copyOf(graph.nodes())) {
            node.attributes().forEach((key, value) -> {
                if (key.equals("annotation.Value.value")) property(graph, node, unwrapPlaceholder(value), "value");
                if (key.equals("annotation.ConfigurationProperties.value") || key.equals("annotation.ConfigurationProperties.prefix")) {
                    property(graph, node, value, "configuration-properties");
                }
            });
        }
    }

    private void property(CodeGraph graph, GraphNode node, String name, String style) {
        if (name == null || name.isBlank()) return;
        String propertyId = "property:" + name;
        graph.upsertNode(new GraphNode(propertyId, EntityKind.CONFIGURATION_PROPERTY, name,
                Map.of("style", style), node.provenance()), true);
        graph.addEdge(new GraphEdge(propertyId, node.id(), RelationKind.CONFIGURES,
                Map.of("resolution", style), framework(node.provenance())));
    }

    /** Links a declared query to the tables it reads or writes, through the entity model when needed. */
    private void modelQueries(CodeGraph graph, Map<String, String> tablesByEntityName) {
        for (GraphNode node : List.copyOf(graph.nodes())) {
            String query = node.attributes().get("annotation.Query.value");
            if (query == null || query.isBlank()) continue;
            boolean nativeQuery = "true".equalsIgnoreCase(node.attributes().get("annotation.Query.nativeQuery"));
            for (String name : SqlTables.referenced(query)) {
                String tableId = tablesByEntityName.getOrDefault(name.toLowerCase(java.util.Locale.ROOT), "table:" + name);
                graph.upsertNode(new GraphNode(tableId, EntityKind.DATABASE_TABLE, tableId.substring("table:".length()),
                        Map.of("inferred", "true", "source", nativeQuery ? "native-query" : "jpql"), node.provenance()));
                graph.addEdge(new GraphEdge(node.id(), tableId, RelationKind.PERSISTS,
                        Map.of("resolution", nativeQuery ? "native-query" : "jpql", "query", query), framework(node.provenance())));
            }
        }
    }

    /** A literal topic passed to a Kafka template send is a publisher relationship. */
    private void modelProducers(CodeGraph graph) {
        for (GraphEdge edge : List.copyOf(graph.edges())) {
            if (edge.kind() != RelationKind.CALLS) continue;
            String topic = edge.attributes().get("arg0");
            if (topic == null || topic.isBlank()) continue;
            String target = edge.to();
            if (!target.contains("KafkaTemplate") && !target.contains("StreamBridge")) continue;
            if (PRODUCER_METHODS.stream().noneMatch(method -> target.contains("#" + method + "("))) continue;
            String topicId = "topic:" + topic;
            graph.upsertNode(new GraphNode(topicId, EntityKind.TOPIC, topic, Map.of("direction", "produced"), edge.provenance()));
            graph.addEdge(new GraphEdge(edge.from(), topicId, RelationKind.PUBLISHES,
                    Map.of("resolution", "kafka-template"), framework(edge.provenance())));
        }
    }

    /** Declared HTTP clients become external services, so an outbound dependency is visible. */
    private void modelRestClients(CodeGraph graph) {
        for (GraphNode node : List.copyOf(graph.nodes())) {
            String feign = node.attributes().get("annotation.FeignClient.name");
            if (feign == null) feign = node.attributes().get("annotation.FeignClient.value");
            if (feign != null && !feign.isBlank()) {
                String serviceId = "service:" + feign;
                graph.upsertNode(new GraphNode(serviceId, EntityKind.EXTERNAL_SERVICE, feign,
                        Map.of("client", "feign", "url", node.attributes().getOrDefault("annotation.FeignClient.url", "")), node.provenance()), true);
                graph.addEdge(new GraphEdge(node.id(), serviceId, RelationKind.DEPENDS_ON,
                        Map.of("resolution", "feign-client"), framework(node.provenance())));
            }
        }
        for (GraphEdge edge : List.copyOf(graph.edges())) {
            if (edge.kind() != RelationKind.DEPENDS_ON && edge.kind() != RelationKind.CALLS) continue;
            String declaredType = edge.to().startsWith("type:") ? edge.to().substring("type:".length()) : "";
            String owner = declaredType.contains("#") ? declaredType.substring(0, declaredType.indexOf('#')) : declaredType;
            if (!REST_CLIENT_TYPES.contains(owner)) continue;
            String serviceId = "service:" + owner;
            graph.upsertNode(new GraphNode(serviceId, EntityKind.EXTERNAL_SERVICE, owner,
                    Map.of("client", "http", "url", edge.attributes().getOrDefault("arg0", "")), edge.provenance()));
            graph.addEdge(new GraphEdge(edge.from(), serviceId, RelationKind.DEPENDS_ON,
                    Map.of("resolution", "http-client"), framework(edge.provenance())));
        }
    }

    /** Marks types whose JSON shape is part of a published contract. */
    private void modelSerialization(CodeGraph graph) {
        Map<String, Set<String>> serialized = new LinkedHashMap<>();
        for (GraphNode node : graph.nodes()) {
            for (String key : node.attributes().keySet()) {
                if (!key.startsWith("annotation.Json")) continue;
                String owner = node.kind() == EntityKind.FIELD || node.kind() == EntityKind.METHOD ? declaringType(node.id()) : node.id();
                serialized.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(key.substring("annotation.".length()));
            }
        }
        serialized.forEach((typeId, annotations) -> graph.node(typeId).ifPresent(type -> {
            Map<String, String> attributes = new LinkedHashMap<>(type.attributes());
            attributes.put("jsonContract", "true");
            attributes.put("jsonAnnotations", String.join(",", annotations));
            graph.upsertNode(new GraphNode(type.id(), type.kind(), type.name(), Map.copyOf(attributes), type.provenance()), true);
        }));
    }

    private Map<String, List<GraphNode>> implementationsByInterface(CodeGraph graph) {
        Map<String, List<GraphNode>> byInterface = new HashMap<>();
        for (GraphEdge edge : graph.edges()) {
            if (edge.kind() != RelationKind.IMPLEMENTS && edge.kind() != RelationKind.EXTENDS) continue;
            graph.node(edge.from()).ifPresent(implementation ->
                    byInterface.computeIfAbsent(edge.to(), ignored -> new ArrayList<>()).add(implementation));
        }
        byInterface.values().forEach(list -> list.sort(java.util.Comparator.comparing(GraphNode::id)));
        return byInterface;
    }

    private Map<String, String> tablesByEntityName(CodeGraph graph) {
        Map<String, String> tables = new HashMap<>();
        for (GraphEdge edge : graph.edges()) {
            if (edge.kind() != RelationKind.PERSISTS || !edge.to().startsWith("table:")) continue;
            graph.node(edge.from()).filter(node -> node.kind() == EntityKind.ENTITY).ifPresent(entity -> {
                String simple = entity.name().substring(entity.name().lastIndexOf('.') + 1);
                tables.put(simple.toLowerCase(java.util.Locale.ROOT), edge.to());
            });
        }
        return tables;
    }

    private boolean isComponent(GraphNode node) {
        if (node.kind() == EntityKind.SERVICE || node.kind() == EntityKind.CONTROLLER
                || node.kind() == EntityKind.REPOSITORY_COMPONENT || node.kind() == EntityKind.CONFIGURATION) return true;
        String annotations = node.attributes().getOrDefault("annotations", "");
        return COMPONENT_KINDS_ANNOTATIONS.stream().anyMatch(annotations::contains);
    }

    private static String resolveTypeId(CodeGraph graph, String declaredType) {
        if (declaredType.isBlank()) return null;
        String erased = declaredType.replaceAll("<.*>", "");
        String simple = erased.substring(erased.lastIndexOf('.') + 1);
        String target = "." + simple;
        Optional<GraphNode> match = graph.nodes().stream()
                .filter(node -> node.id().startsWith("type:"))
                .filter(node -> node.id().endsWith(target) || node.id().equals("type:" + simple))
                .min(java.util.Comparator.comparing(GraphNode::id));
        return match.map(GraphNode::id).orElse(null);
    }

    private static String declaringType(String memberId) {
        int field = memberId.indexOf(".field:");
        if (field > 0) return memberId.substring(0, field);
        int method = memberId.indexOf('#');
        return method > 0 ? memberId.substring(0, method) : memberId;
    }

    private static String unwrapPlaceholder(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) trimmed = trimmed.substring(2, trimmed.length() - 1);
        int defaulted = trimmed.indexOf(':');
        return defaulted < 0 ? trimmed : trimmed.substring(0, defaulted);
    }

    private static Provenance framework(Provenance origin) {
        return new Provenance(FRAMEWORK_RESOLVER, 0.9, origin.file(), origin.line(), origin.column());
    }
}

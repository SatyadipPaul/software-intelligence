package io.softwareintelligence.indextree;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.RelationKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Derives the table of contents from the graph's own containment.
 *
 * <p>Two axes hang off the root, and both are views of the same graph:
 *
 * <pre>{@code
 * index:root
 *   ├── capabilities   route and topic groupings, present only where the graph has them
 *   └── modules        module -> package -> type -> member, always present
 * }</pre>
 *
 * <p>The capability axis exists only when the framework layer proved endpoints or topics, so a
 * repository with no framework — a library, say — gets the structural axis alone rather than an
 * invented one. A type can appear under both axes; the axes are lenses, and a node is a pointer, so
 * nothing is duplicated except the path used to reach it.
 *
 * <p>Everything is sorted at every level. Two derivations of the same graph produce the same tree,
 * which is what lets a navigator's descent be compared across runs.
 */
public final class IndexTreeBuilder {

    /**
     * Shape limits. Fan-out is the load-bearing one: a package with 500 types produces a card no
     * reader can scan and no scorer can discriminate over, so oversized sibling sets are bucketed
     * into alphabetical groups rather than presented whole or silently truncated.
     */
    public record Options(int maxFanout, int maxMembersPerType, boolean includeMembers) {
        public static Options defaults() { return new Options(24, 24, true); }

        public Options {
            if (maxFanout < 2) throw new IllegalArgumentException("maxFanout must be at least 2, was " + maxFanout);
            if (maxMembersPerType < 0) throw new IllegalArgumentException("maxMembersPerType must not be negative");
        }
    }

    private final CodeGraph graph;
    private final Options options;
    private final Map<String, IndexNode> nodes = new LinkedHashMap<>();

    private IndexTreeBuilder(CodeGraph graph, Options options) {
        this.graph = graph;
        this.options = options;
    }

    public static IndexTree derive(CodeGraph graph) {
        return derive(graph, Options.defaults());
    }

    public static IndexTree derive(CodeGraph graph, Options options) {
        return new IndexTreeBuilder(graph, options).build();
    }

    private IndexTree build() {
        List<String> rootChildren = new ArrayList<>();
        rootChildren.addAll(capabilityAxis());
        rootChildren.addAll(moduleAxis());

        Map<String, String> facts = new TreeMap<>();
        facts.put("graphNodes", Integer.toString(graph.nodes().size()));
        facts.put("graphEdges", Integer.toString(graph.edges().size()));
        put(new IndexNode(IndexTree.ROOT_ID, IndexKind.ROOT, repositoryName(), List.of(),
                bucket(IndexTree.ROOT_ID, rootChildren), facts));

        // Emitted root-first, then in the order children were created, so the file reads top-down.
        List<IndexNode> ordered = new ArrayList<>();
        ordered.add(nodes.get(IndexTree.ROOT_ID));
        nodes.values().stream().filter(node -> !node.id().equals(IndexTree.ROOT_ID)).forEach(ordered::add);
        return new IndexTree(GraphFingerprint.of(graph), ordered);
    }

    // ---------------------------------------------------------------- capability axis

    private List<String> capabilityAxis() {
        List<String> ids = new ArrayList<>();
        List<GraphNode> capabilities = graph.nodes().stream()
                .filter(node -> node.kind() == EntityKind.BUSINESS_CAPABILITY)
                .sorted(Comparator.comparing(GraphNode::id)).toList();
        for (GraphNode capability : capabilities) {
            List<String> children = new ArrayList<>();
            for (GraphEdge edge : sorted(graph.outgoing(capability.id()))) {
                if (edge.kind() != RelationKind.PARTICIPATES_IN) continue;
                Optional<GraphNode> member = graph.node(edge.to());
                if (member.isEmpty()) continue;
                IndexKind kind = operationalKind(member.get());
                if (kind == null) continue;
                children.add(operationalLeaf(member.get(), kind));
            }
            Map<String, String> facts = new TreeMap<>(capability.attributes());
            facts.put("members", Integer.toString(children.size()));
            copySummary(capability, facts);
            ids.add(put(new IndexNode("index:" + capability.id(), IndexKind.CAPABILITY, capability.name(),
                    List.of(capability.id()), bucket("index:" + capability.id(), children), facts)).id());
        }
        return ids;
    }

    /** A capability's members are the operational surface it groups; anything else is not a leaf here. */
    private static IndexKind operationalKind(GraphNode node) {
        return switch (node.kind()) {
            case ENDPOINT -> IndexKind.ENDPOINT;
            case TOPIC -> IndexKind.TOPIC;
            case DATABASE_TABLE -> IndexKind.TABLE;
            case ENTITY -> IndexKind.TYPE;
            default -> null;
        };
    }

    private String operationalLeaf(GraphNode node, IndexKind kind) {
        String id = "index:" + node.id();
        if (nodes.containsKey(id)) return id;
        Map<String, String> facts = new TreeMap<>(node.attributes());
        facts.put("declaredAt", location(node));
        copySummary(node, facts);
        return put(new IndexNode(id, kind, node.name(), List.of(node.id()), List.of(), facts)).id();
    }

    // ---------------------------------------------------------------- structural axis

    private List<String> moduleAxis() {
        Map<String, List<String>> typesByModule = typesByModule();
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : typesByModule.entrySet()) {
            String moduleId = entry.getKey();
            List<String> types = entry.getValue();
            Optional<GraphNode> moduleNode = graph.node(moduleId);
            String name = moduleNode.map(GraphNode::name).orElse(repositoryName());
            String indexId = "index:" + moduleId;

            List<String> children = packageLevel(indexId, types);
            Map<String, String> facts = new TreeMap<>(moduleNode.map(GraphNode::attributes).orElse(Map.of()));
            facts.put("types", Integer.toString(types.size()));
            facts.put("exemplars", exemplars(types));
            moduleNode.ifPresent(node -> copySummary(node, facts));
            ids.add(put(new IndexNode(indexId, IndexKind.MODULE, name, moduleNode.map(node -> List.of(node.id())).orElse(List.of()),
                    bucket(indexId, children), facts)).id());
        }
        return ids;
    }

    /**
     * Groups a module's types by declared package, and collapses the level away when it would add
     * nothing — a single package under a module is a chain of one, which is noise on a card and an
     * extra descent step for a navigator.
     */
    private List<String> packageLevel(String moduleIndexId, List<String> types) {
        Map<String, List<String>> byPackage = new TreeMap<>();
        for (String type : types) byPackage.computeIfAbsent(packageOf(type), ignored -> new ArrayList<>()).add(type);
        if (byPackage.size() <= 1) return typeLevel(types);

        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : byPackage.entrySet()) {
            String packageName = entry.getKey();
            // Keyed by module: the same package name can legitimately appear under two source
            // roots, and a tree cannot give one node two parents.
            String indexId = moduleIndexId + "|package:" + packageName;
            Map<String, String> facts = new TreeMap<>();
            facts.put("types", Integer.toString(entry.getValue().size()));
            facts.put("exemplars", exemplars(entry.getValue()));
            graph.node("package:" + packageName).ifPresent(node -> copySummary(node, facts));
            List<String> graphIds = graph.node("package:" + packageName).map(node -> List.of(node.id())).orElse(List.of());
            ids.add(put(new IndexNode(indexId, IndexKind.PACKAGE, packageName, graphIds,
                    bucket(indexId, typeLevel(entry.getValue())), facts)).id());
        }
        return ids;
    }

    private List<String> typeLevel(List<String> types) {
        List<String> ids = new ArrayList<>();
        for (String type : new TreeSet<>(types)) {
            GraphNode node = graph.node(type).orElse(null);
            if (node == null) continue;
            String indexId = "index:" + type;
            List<String> members = options.includeMembers() ? memberLevel(type) : List.of();
            Map<String, String> facts = new TreeMap<>(node.attributes());
            facts.put("declaredAt", location(node));
            facts.put("kind", node.kind().name());
            facts.put("callers", Integer.toString(graph.incoming(type).size()));
            copySummary(node, facts);
            ids.add(put(new IndexNode(indexId, IndexKind.TYPE, node.name(), List.of(type),
                    bucket(indexId, members), facts)).id());
        }
        return ids;
    }

    /**
     * Methods, most-referenced first, capped. The cap is a card-size limit rather than a claim that
     * the rest do not exist: the type node still covers every member through its own graph id, so a
     * navigator that stops at the type loses nothing an anchor needs.
     */
    private List<String> memberLevel(String type) {
        List<GraphNode> methods = new ArrayList<>();
        for (GraphEdge edge : sorted(graph.outgoing(type))) {
            if (edge.kind() != RelationKind.DECLARES) continue;
            graph.node(edge.to()).filter(node -> node.kind() == EntityKind.METHOD).ifPresent(methods::add);
        }
        methods.sort(Comparator.comparingInt((GraphNode node) -> graph.incoming(node.id()).size()).reversed()
                .thenComparing(GraphNode::id));
        List<String> ids = new ArrayList<>();
        for (GraphNode method : methods.stream().limit(options.maxMembersPerType()).toList()) {
            Map<String, String> facts = new TreeMap<>(method.attributes());
            facts.put("declaredAt", location(method));
            facts.put("callers", Integer.toString(graph.incoming(method.id()).size()));
            copySummary(method, facts);
            ids.add(put(new IndexNode("index:" + method.id(), IndexKind.MEMBER, method.name(),
                    List.of(method.id()), List.of(), facts)).id());
        }
        // Emitted in id order so the tree file is stable; the reference ranking above only decides
        // which members survive the cap, not how they are presented.
        return ids.stream().sorted().toList();
    }

    // ---------------------------------------------------------------- module and package membership

    /**
     * Maps every repository type to the module that contains it, reading the {@code MODULE} nodes
     * the architecture layer emitted. With that layer switched off there are no module nodes, so
     * everything hangs off one synthetic module and the package level carries the structure.
     */
    private Map<String, List<String>> typesByModule() {
        Map<String, List<String>> byModule = new TreeMap<>();
        Map<String, String> moduleOfType = new LinkedHashMap<>();
        for (GraphNode node : graph.nodes()) {
            if (node.kind() != EntityKind.MODULE) continue;
            byModule.computeIfAbsent(node.id(), ignored -> new ArrayList<>());
            for (GraphEdge edge : sorted(graph.outgoing(node.id()))) {
                if (edge.kind() == RelationKind.CONTAINS) moduleOfType.put(edge.to(), node.id());
            }
        }
        List<String> orphans = new ArrayList<>();
        for (GraphNode node : graph.nodes()) {
            if (!isRepositoryType(node)) continue;
            String module = moduleOfType.get(node.id());
            if (module == null) orphans.add(node.id());
            else byModule.computeIfAbsent(module, ignored -> new ArrayList<>()).add(node.id());
        }
        if (!orphans.isEmpty()) {
            // A type the architecture layer never placed still has to be reachable, or navigation
            // would silently lose it. It goes under a synthetic module named for the repository.
            byModule.computeIfAbsent("module:" + repositoryName(), ignored -> new ArrayList<>()).addAll(orphans);
        }
        byModule.values().forEach(java.util.Collections::sort);
        byModule.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return byModule;
    }

    /**
     * Mirrors the architecture layer's definition of a type declared in this repository: a
     * {@code type:} id that is not a member, not external, and has a declaration edge pointing at
     * it.
     */
    private boolean isRepositoryType(GraphNode node) {
        if (!node.id().startsWith("type:") || node.id().contains("#") || node.id().contains(".field:")) return false;
        if (node.kind() == EntityKind.EXTERNAL_SYMBOL) return false;
        return graph.incoming(node.id()).stream().anyMatch(edge -> edge.kind() == RelationKind.DECLARES);
    }

    /**
     * The package a type is declared in, read from the graph rather than sliced off the qualified
     * name: a nested type is named {@code pkg.Outer.Inner}, and trimming the last segment would put
     * every outer class in a package of its own.
     */
    private String packageOf(String type) {
        String file = declaringFile(type, 0);
        if (file != null) {
            for (GraphEdge edge : sorted(graph.outgoing(file))) {
                if (edge.kind() == RelationKind.DECLARES && edge.to().startsWith("package:")) {
                    return edge.to().substring("package:".length());
                }
            }
        }
        return packageFromName(graph.node(type).map(GraphNode::name).orElse(""));
    }

    private String declaringFile(String id, int depth) {
        if (depth > 16) return null;
        for (GraphEdge edge : sorted(graph.incoming(id))) {
            if (edge.kind() != RelationKind.DECLARES) continue;
            if (edge.from().startsWith("file:")) return edge.from();
            String enclosing = declaringFile(edge.from(), depth + 1);
            if (enclosing != null) return enclosing;
        }
        return null;
    }

    /** Java's own convention: package segments are lower case, type segments are capitalized. */
    private static String packageFromName(String qualifiedName) {
        StringBuilder packageName = new StringBuilder();
        for (String segment : qualifiedName.split("\\.")) {
            if (segment.isEmpty() || Character.isUpperCase(segment.charAt(0))) break;
            if (packageName.length() > 0) packageName.append('.');
            packageName.append(segment);
        }
        return packageName.length() == 0 ? "<default>" : packageName.toString();
    }

    // ---------------------------------------------------------------- fan-out

    /**
     * Keeps a sibling set readable. Oversized sets are chunked into alphabetical groups, recursively
     * if the groups themselves are too many, so no card ever presents more than {@code maxFanout}
     * choices and nothing is dropped to achieve that.
     */
    private List<String> bucket(String parentId, List<String> children) {
        if (children.size() <= options.maxFanout()) return List.copyOf(children);
        List<String> groups = new ArrayList<>();
        int size = (int) Math.ceil((double) children.size() / options.maxFanout());
        for (int start = 0, index = 0; start < children.size(); start += size, index++) {
            List<String> slice = children.subList(start, Math.min(children.size(), start + size));
            String groupId = parentId + "#group" + index;
            Map<String, String> facts = new TreeMap<>();
            facts.put("entries", Integer.toString(slice.size()));
            facts.put("exemplars", String.join(", ", slice.stream().limit(4).map(this::displayName).toList()));
            groups.add(put(new IndexNode(groupId, IndexKind.GROUP, range(slice), List.of(),
                    List.copyOf(slice), facts)).id());
        }
        return bucket(parentId, groups);
    }

    private String range(List<String> slice) {
        String first = displayName(slice.get(0));
        String last = displayName(slice.get(slice.size() - 1));
        // ASCII on purpose: a group name is printed to a console whose encoding we do not control.
        return first.equals(last) ? first : first + " to " + last;
    }

    private String displayName(String indexId) {
        IndexNode node = nodes.get(indexId);
        return node == null ? indexId : node.name();
    }

    // ---------------------------------------------------------------- helpers

    private IndexNode put(IndexNode node) {
        nodes.put(node.id(), node);
        return node;
    }

    /**
     * Copies a pinned enrichment claim onto the card, and only a claim: {@code claim.name} and
     * {@code claim.summary} are what a model is allowed to author, they arrive already verified and
     * confidence-capped, and they are marked as claims here so a reader of a card can tell a
     * counted fact from a described one.
     */
    private static void copySummary(GraphNode node, Map<String, String> facts) {
        String summary = node.attributes().get("claim.summary");
        if (summary == null) summary = node.attributes().get("claim.name");
        if (summary != null && !summary.isBlank()) facts.put("summary", summary);
    }

    private String repositoryName() {
        return graph.nodes().stream().filter(node -> node.kind() == EntityKind.REPOSITORY)
                .map(GraphNode::name).sorted().findFirst().orElse("repository");
    }

    private String exemplars(List<String> typeIds) {
        return String.join(", ", typeIds.stream().sorted()
                .map(id -> graph.node(id).map(GraphNode::name).orElse(id))
                .map(IndexTreeBuilder::simpleName).limit(6).toList());
    }

    private static String simpleName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }

    private String location(GraphNode node) {
        return node.provenance().file().isBlank() ? "" : node.provenance().file() + ":" + node.provenance().line();
    }

    /** Edge iteration that never depends on insertion order, because that order reaches the tree. */
    private static List<GraphEdge> sorted(List<GraphEdge> edges) {
        return edges.stream()
                .sorted(Comparator.comparing(GraphEdge::to).thenComparing(GraphEdge::from)
                        .thenComparing(edge -> edge.kind().name())
                        .thenComparing(edge -> edge.provenance().line())
                        .thenComparing(edge -> edge.provenance().column()))
                .toList();
    }

    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}

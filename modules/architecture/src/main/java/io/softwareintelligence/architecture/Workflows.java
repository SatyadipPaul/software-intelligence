package io.softwareintelligence.architecture;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.Provenance;
import io.softwareintelligence.model.RelationKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers end-to-end workflows: an entry point (an HTTP endpoint or a consumed topic) followed
 * forward through calls to whatever it ultimately touches — tables, topics, external services.
 *
 * <p>A workflow is a summary of edges that already exist, so it inherits their evidence. The
 * workflow node records the entry point's source position, and every step keeps the id of the edge
 * that justified it.
 */
public final class Workflows {
    public static final String RESOLVER = "WORKFLOW_TRAVERSAL";

    public record Workflow(String id, String entryPoint, List<String> steps, List<String> touches) { }

    private static final Set<RelationKind> FORWARD =
            Set.of(RelationKind.CALLS, RelationKind.EXPOSES, RelationKind.DECLARES);
    private static final Set<RelationKind> EFFECTS =
            Set.of(RelationKind.PERSISTS, RelationKind.PUBLISHES, RelationKind.CONSUMES, RelationKind.WRITES);

    private Workflows() { }

    /** Finds every workflow and records each one in the graph as a {@code WORKFLOW} node. */
    public static List<Workflow> discover(CodeGraph graph, int maxDepth) {
        List<Workflow> workflows = new ArrayList<>();
        List<GraphNode> entryPoints = graph.nodes().stream()
                .filter(node -> node.kind() == EntityKind.ENDPOINT || node.kind() == EntityKind.TOPIC)
                .sorted(Comparator.comparing(GraphNode::id)).toList();
        for (GraphNode entry : entryPoints) {
            Set<String> visited = new LinkedHashSet<>(Set.of(entry.id()));
            Set<String> touches = new LinkedHashSet<>();
            List<String> steps = new ArrayList<>();
            ArrayDeque<Step> queue = new ArrayDeque<>(List.of(new Step(entry.id(), 0)));
            // An endpoint points at the method it exposes, but a consumed topic is pointed *at* by
            // its listener. Seed from the listener so a message-driven flow is traversed too.
            if (entry.kind() == EntityKind.TOPIC) {
                for (GraphEdge edge : sorted(graph.incoming(entry.id()))) {
                    if (edge.kind() != RelationKind.CONSUMES || !visited.add(edge.from())) continue;
                    steps.add(edge.from());
                    queue.add(new Step(edge.from(), 1));
                }
            }
            while (!queue.isEmpty()) {
                Step current = queue.remove();
                if (current.depth() >= maxDepth) continue;
                for (GraphEdge edge : sorted(graph.outgoing(current.id()))) {
                    if (EFFECTS.contains(edge.kind())) {
                        touches.add(edge.to());
                        continue;
                    }
                    if (!FORWARD.contains(edge.kind()) || !visited.add(edge.to())) continue;
                    if (graph.node(edge.to()).map(GraphNode::kind).filter(kind -> kind == EntityKind.METHOD).isEmpty()) continue;
                    steps.add(edge.to());
                    queue.add(new Step(edge.to(), current.depth() + 1));
                }
                // An effect recorded on the enclosing type (a table, a topic) still belongs to the flow.
                graph.node(current.id()).ifPresent(node -> {
                    for (GraphEdge edge : sorted(graph.outgoing(Communities.owningType(node.id())))) {
                        if (EFFECTS.contains(edge.kind())) touches.add(edge.to());
                    }
                });
            }
            if (steps.isEmpty() && touches.isEmpty()) continue;
            Workflow workflow = new Workflow("workflow:" + entry.id(), entry.id(), List.copyOf(steps), List.copyOf(touches));
            workflows.add(workflow);
            record(graph, entry, workflow);
        }
        return List.copyOf(workflows);
    }

    private static void record(CodeGraph graph, GraphNode entry, Workflow workflow) {
        Provenance provenance = new Provenance(RESOLVER, 0.9, entry.provenance().file(), entry.provenance().line(), entry.provenance().column());
        graph.upsertNode(new GraphNode(workflow.id(), EntityKind.WORKFLOW, entry.name(),
                Map.of("entryPoint", entry.id(), "steps", Integer.toString(workflow.steps().size()),
                        "touches", String.join(",", workflow.touches())), provenance), true);
        graph.addEdge(new GraphEdge(workflow.id(), entry.id(), RelationKind.PARTICIPATES_IN, Map.of("role", "entry-point"), provenance));
        for (String step : workflow.steps()) {
            graph.addEdge(new GraphEdge(workflow.id(), step, RelationKind.PARTICIPATES_IN, Map.of("role", "step"), provenance));
        }
        for (String touched : workflow.touches()) {
            graph.addEdge(new GraphEdge(workflow.id(), touched, RelationKind.PARTICIPATES_IN, Map.of("role", "effect"), provenance));
        }
    }

    private static List<GraphEdge> sorted(List<GraphEdge> edges) {
        return edges.stream().sorted(Comparator.comparing(GraphEdge::to).thenComparing(edge -> edge.kind().name())).toList();
    }

    private record Step(String id, int depth) { }
}

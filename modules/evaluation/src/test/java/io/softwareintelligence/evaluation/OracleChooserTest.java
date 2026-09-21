package io.softwareintelligence.evaluation;

import io.softwareintelligence.indextree.IndexTree;
import io.softwareintelligence.indextree.IndexTreeBuilder;
import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.Provenance;
import io.softwareintelligence.model.RelationKind;
import io.softwareintelligence.queryengine.TreeNavigator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleChooserTest {

    private static final Provenance SOURCE = new Provenance("JDT_AST", 1.0, "src/main/java/x.java", 5, 1);

    /** Two modules, one holding the answer. Built the way the tree builder actually reads a graph. */
    private static CodeGraph graph() {
        CodeGraph graph = new CodeGraph();
        graph.upsertNode(new GraphNode("repo:demo", EntityKind.REPOSITORY, "demo", Map.of(), SOURCE), true);
        declareType(graph, "type:alpha.Wanted", "alpha");
        declareType(graph, "type:alpha.Other", "alpha");
        declareType(graph, "type:beta.Elsewhere", "beta");
        for (String module : List.of("alpha", "beta")) {
            graph.upsertNode(new GraphNode("module:" + module, EntityKind.MODULE, module, Map.of(), SOURCE), true);
        }
        graph.addEdge(new GraphEdge("module:alpha", "type:alpha.Wanted", RelationKind.CONTAINS, Map.of(), SOURCE));
        graph.addEdge(new GraphEdge("module:alpha", "type:alpha.Other", RelationKind.CONTAINS, Map.of(), SOURCE));
        graph.addEdge(new GraphEdge("module:beta", "type:beta.Elsewhere", RelationKind.CONTAINS, Map.of(), SOURCE));
        return graph;
    }

    private static void declareType(CodeGraph graph, String id, String packageName) {
        String simple = id.substring(id.lastIndexOf('.') + 1);
        String file = "src/main/java/" + packageName + "/" + simple + ".java";
        Provenance provenance = new Provenance("JDT_AST", 1.0, file, 5, 1);
        graph.upsertNode(new GraphNode("file:" + file, EntityKind.FILE, file, Map.of(), provenance), true);
        graph.upsertNode(new GraphNode("package:" + packageName, EntityKind.PACKAGE, packageName, Map.of(), provenance), true);
        graph.upsertNode(new GraphNode(id, EntityKind.TYPE, id.substring("type:".length()), Map.of(), provenance), true);
        graph.addEdge(new GraphEdge("file:" + file, "package:" + packageName, RelationKind.DECLARES, Map.of(), provenance));
        graph.addEdge(new GraphEdge("file:" + file, id, RelationKind.DECLARES, Map.of(), provenance));
    }

    @Test void it_descends_towards_the_answer_whatever_the_ranking_says() {
        IndexTree tree = IndexTreeBuilder.derive(graph());
        OracleChooser oracle = OracleChooser.forTargets(tree, Set.of("type:alpha.Wanted"));

        // Present the branches in the worst possible order: the wrong one scores highest.
        List<TreeNavigator.Scored> ranked = tree.children(tree.root()).stream()
                .map(node -> new TreeNavigator.Scored(node, node.name().startsWith("beta") ? 9.0 : 0.0, false, "test"))
                .sorted(java.util.Comparator.comparingDouble(TreeNavigator.Scored::score).reversed())
                .toList();

        List<String> chosen = oracle.choose(tree.root(), ranked, 1);
        assertEquals(1, chosen.size());
        assertTrue(chosen.get(0).contains("alpha"), "expected the branch holding the answer, got " + chosen);
    }

    @Test void with_no_branch_leading_anywhere_it_falls_back_to_the_ranking() {
        // The descent is already lost; an oracle that returned nothing would end the run early and
        // flatter the ceiling it exists to measure.
        IndexTree tree = IndexTreeBuilder.derive(graph());
        OracleChooser oracle = OracleChooser.forTargets(tree, Set.of("type:nowhere.Missing"));
        List<TreeNavigator.Scored> ranked = tree.children(tree.root()).stream()
                .map(node -> new TreeNavigator.Scored(node, 1.0, false, "test")).toList();
        assertEquals(1, oracle.choose(tree.root(), ranked, 1).size());
    }
}

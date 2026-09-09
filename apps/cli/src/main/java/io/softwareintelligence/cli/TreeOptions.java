package io.softwareintelligence.cli;

import io.softwareintelligence.indextree.IndexTree;
import io.softwareintelligence.indextree.IndexTreeBuilder;
import io.softwareintelligence.indextree.IndexTreeJson;
import io.softwareintelligence.model.CodeGraph;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Gets the index tree a command should use.
 *
 * <p>Derivation is deterministic and cheap, so the default is to derive one from the graph in
 * memory and never mention files at all. A pinned tree is for the workflows that need one to be
 * stable across invocations — an assistant descent, a shared review — and it is checked against the
 * graph before it is used, because an anchor from a stale tree points at a symbol that may no
 * longer exist.
 */
final class TreeOptions {
    private TreeOptions() { }

    static IndexTree load(CodeGraph graph, Path pinned) throws IOException {
        if (pinned == null) return IndexTreeBuilder.derive(graph);
        IndexTree tree = IndexTreeJson.read(pinned);
        IndexTreeJson.requireCurrent(tree, graph);
        return tree;
    }
}

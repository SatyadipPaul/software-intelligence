package io.softwareintelligence.analyzer.java;

import io.softwareintelligence.model.CodeGraph;
import io.softwareintelligence.model.EntityKind;
import io.softwareintelligence.model.GraphEdge;
import io.softwareintelligence.model.GraphNode;
import io.softwareintelligence.model.Provenance;
import io.softwareintelligence.model.RelationKind;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.stream.Stream;

/**
 * The initial deterministic Java analyzer. It deliberately preserves unresolved symbols;
 * later JDT/SCIP resolution can upgrade their provenance without discarding evidence.
 */
public final class JavaRepositoryAnalyzer {
    public CodeGraph analyze(Path repository) throws IOException {
        Path root = repository.toAbsolutePath().normalize();
        GraphBuilder graph = new GraphBuilder();
        graph.node("repo:" + root, EntityKind.REPOSITORY, root.getFileName().toString(), Map.of(), new Provenance("FILESYSTEM", 1.0, "", 0, 0));
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> parse(root, path, graph));
        }
        new IntraRepositoryResolver().resolve(graph.graph());
        return graph.graph();
    }

    private void parse(Path root, Path path, GraphBuilder graph) {
        try {
            String source = Files.readString(path);
            String relative = root.relativize(path.toAbsolutePath()).toString().replace('\\', '/');
            String fileId = "file:" + relative;
            ASTParser parser = ASTParser.newParser(AST.JLS25);
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setSource(source.toCharArray());
            Map<String, String> compilerOptions = JavaCore.getOptions();
            JavaCore.setComplianceOptions(JavaCore.VERSION_25, compilerOptions);
            parser.setCompilerOptions(compilerOptions);
            parser.setUnitName(path.getFileName().toString());
            parser.setResolveBindings(false);
            parser.setBindingsRecovery(true);
            CompilationUnit unit = (CompilationUnit) parser.createAST(null);
            graph.node(fileId, EntityKind.FILE, relative, Map.of(), provenance(unit, 0, relative, false));
            graph.edge("repo:" + root, fileId, RelationKind.CONTAINS, Map.of(), provenance(unit, 0, relative, false));
            unit.accept(new Collector(unit, relative, fileId, graph));
        } catch (IOException failure) {
            throw new IllegalStateException("Could not analyze " + path, failure);
        }
    }

    private static Provenance provenance(CompilationUnit unit, int offset, String file, boolean unresolved) {
        return unresolved
                ? Provenance.unresolved(file, unit.getLineNumber(offset), unit.getColumnNumber(offset) + 1)
                : Provenance.syntax(file, unit.getLineNumber(offset), unit.getColumnNumber(offset) + 1);
    }

    private static final class Collector extends ASTVisitor {
        private final CompilationUnit unit;
        private final String file;
        private final String fileId;
        private final GraphBuilder graph;
        private final Deque<String> types = new ArrayDeque<>();
        private final Deque<String> typePaths = new ArrayDeque<>();
        private final Deque<String> methods = new ArrayDeque<>();
        private String packageName = "";

        private Collector(CompilationUnit unit, String file, String fileId, GraphBuilder graph) {
            this.unit = unit;
            this.file = file;
            this.fileId = fileId;
            this.graph = graph;
        }

        @Override public boolean visit(PackageDeclaration declaration) {
            packageName = declaration.getName().getFullyQualifiedName();
            String packageId = "package:" + packageName;
            graph.node(packageId, EntityKind.PACKAGE, packageName, Map.of(), p(declaration.getStartPosition(), false));
            graph.edge(fileId, packageId, RelationKind.DECLARES, Map.of(), p(declaration.getStartPosition(), false));
            return true;
        }

        @Override public boolean visit(ImportDeclaration declaration) {
            String imported = declaration.getName().getFullyQualifiedName() + (declaration.isOnDemand() ? ".*" : "");
            String externalId = "external:" + imported;
            graph.node(externalId, EntityKind.EXTERNAL_SYMBOL, imported, Map.of("reason", "import"), p(declaration.getStartPosition(), false));
            graph.edge(fileId, externalId, RelationKind.IMPORTS, Map.of("static", Boolean.toString(declaration.isStatic())), p(declaration.getStartPosition(), false));
            return false;
        }

        @Override public boolean visit(TypeDeclaration declaration) {
            String name = qualified(declaration.getName().getIdentifier());
            String id = "type:" + name;
            List<String> annotations = annotationNames(declaration.modifiers());
            graph.node(id, declaration.isInterface() ? EntityKind.INTERFACE : frameworkKind(annotations), name,
                    Map.of("annotations", String.join(",", annotations)), p(declaration.getStartPosition(), false));
            graph.edge(types.isEmpty() ? fileId : types.peek(), id, RelationKind.DECLARES, Map.of(), p(declaration.getStartPosition(), false));
            if (declaration.getSuperclassType() != null) relateType(id, declaration.getSuperclassType().toString(), RelationKind.EXTENDS, declaration.getStartPosition());
            for (Object implemented : declaration.superInterfaceTypes()) relateType(id, implemented.toString(), declaration.isInterface() ? RelationKind.EXTENDS : RelationKind.IMPLEMENTS, declaration.getStartPosition());
            types.push(id);
            if (annotations.contains("Entity")) {
                String table = annotationArgument(declaration.modifiers(), "Table").orElse(declaration.getName().getIdentifier());
                String tableId = "table:" + table;
                graph.node(tableId, EntityKind.DATABASE_TABLE, table, Map.of("inferred", Boolean.toString(!annotations.contains("Table"))), p(declaration.getStartPosition(), false));
                graph.edge(id, tableId, RelationKind.PERSISTS, Map.of(), p(declaration.getStartPosition(), false));
            }
            typePaths.push(annotationArgument(declaration.modifiers(), "RequestMapping").orElse(""));
            return true;
        }

        @Override public void endVisit(TypeDeclaration declaration) { types.pop(); typePaths.pop(); }

        @Override public boolean visit(EnumDeclaration declaration) {
            String name = qualified(declaration.getName().getIdentifier());
            String id = "type:" + name;
            graph.node(id, EntityKind.TYPE, name, Map.of("type", "enum"), p(declaration.getStartPosition(), false));
            graph.edge(types.isEmpty() ? fileId : types.peek(), id, RelationKind.DECLARES, Map.of(), p(declaration.getStartPosition(), false));
            types.push(id);
            typePaths.push("");
            return true;
        }

        @Override public void endVisit(EnumDeclaration declaration) { types.pop(); typePaths.pop(); }

        @Override public boolean visit(FieldDeclaration declaration) {
            if (!types.isEmpty()) {
                String field = declaration.fragments().isEmpty() ? "<unnamed>" : declaration.fragments().get(0).toString().split("=")[0].trim();
                String id = types.peek() + ".field:" + field;
                graph.node(id, EntityKind.FIELD, field, Map.of("declaredType", declaration.getType().toString()), p(declaration.getStartPosition(), false));
                graph.edge(types.peek(), id, RelationKind.DECLARES, Map.of(), p(declaration.getStartPosition(), false));
            }
            return true;
        }

        @Override public boolean visit(MethodDeclaration declaration) {
            if (types.isEmpty()) return true;
            String name = declaration.isConstructor() ? "<init>" : declaration.getName().getIdentifier();
            String methodId = types.peek() + "#" + name + "/" + declaration.parameters().size();
            List<String> annotations = annotationNames(declaration.modifiers());
            graph.node(methodId, EntityKind.METHOD, name, Map.of("arity", Integer.toString(declaration.parameters().size()), "annotations", String.join(",", annotations)), p(declaration.getStartPosition(), false));
            graph.edge(types.peek(), methodId, RelationKind.DECLARES, Map.of(), p(declaration.getStartPosition(), false));
            endpoint(declaration, annotations, methodId);
            kafkaListener(declaration, annotations, methodId);
            if (annotations.contains("Transactional")) {
                String transactionId = "transaction:" + methodId;
                graph.node(transactionId, EntityKind.TRANSACTION, name + " transaction", Map.of(), p(declaration.getStartPosition(), false));
                graph.edge(methodId, transactionId, RelationKind.PARTICIPATES_IN, Map.of(), p(declaration.getStartPosition(), false));
            }
            methods.push(methodId);
            return true;
        }

        @Override public void endVisit(MethodDeclaration declaration) { if (!methods.isEmpty()) methods.pop(); }

        @Override public boolean visit(MethodInvocation invocation) {
            if (methods.isEmpty()) return true;
            String receiver = invocation.getExpression() == null ? "" : invocation.getExpression() + ".";
            String target = receiver + invocation.getName().getIdentifier() + "/" + invocation.arguments().size();
            String targetId = "external:call:" + target;
            graph.node(targetId, EntityKind.EXTERNAL_SYMBOL, target, Map.of("reason", "unresolved-call"), p(invocation.getStartPosition(), true));
            graph.edge(methods.peek(), targetId, RelationKind.CALLS, Map.of(), p(invocation.getStartPosition(), true));
            return true;
        }

        private void relateType(String from, String target, RelationKind kind, int position) {
            String targetId = "external:type:" + target;
            graph.node(targetId, EntityKind.EXTERNAL_SYMBOL, target, Map.of("reason", "unresolved-type"), p(position, true));
            graph.edge(from, targetId, kind, Map.of(), p(position, true));
        }

        private String qualified(String localName) {
            String owner = types.isEmpty() ? packageName : types.peek().substring("type:".length());
            return owner.isBlank() ? localName : owner + "." + localName;
        }

        private Provenance p(int position, boolean unresolved) { return provenance(unit, position, file, unresolved); }

        private void endpoint(MethodDeclaration declaration, List<String> annotations, String methodId) {
            String mapping = annotations.stream().filter(name -> name.endsWith("Mapping")).findFirst().orElse(null);
            if (mapping == null) return;
            String verb = switch (mapping) {
                case "GetMapping" -> "GET";
                case "PostMapping" -> "POST";
                case "PutMapping" -> "PUT";
                case "DeleteMapping" -> "DELETE";
                case "PatchMapping" -> "PATCH";
                default -> "REQUEST";
            };
            String path = joinPaths(typePaths.peek(), annotationArgument(declaration.modifiers(), mapping).orElse("/"));
            String endpointId = "endpoint:" + verb + ":" + path;
            graph.node(endpointId, EntityKind.ENDPOINT, verb + " " + path, Map.of("httpMethod", verb, "path", path), p(declaration.getStartPosition(), false));
            graph.edge(endpointId, methodId, RelationKind.EXPOSES, Map.of(), p(declaration.getStartPosition(), false));
        }

        private void kafkaListener(MethodDeclaration declaration, List<String> annotations, String methodId) {
            if (!annotations.contains("KafkaListener")) return;
            String topic = annotationArgument(declaration.modifiers(), "KafkaListener").orElse("<dynamic-topic>");
            String topicId = "topic:" + topic;
            graph.node(topicId, EntityKind.TOPIC, topic, Map.of(), p(declaration.getStartPosition(), false));
            graph.edge(methodId, topicId, RelationKind.CONSUMES, Map.of(), p(declaration.getStartPosition(), false));
        }

        private static EntityKind frameworkKind(List<String> annotations) {
            if (annotations.contains("RestController") || annotations.contains("Controller")) return EntityKind.CONTROLLER;
            if (annotations.contains("Service")) return EntityKind.SERVICE;
            if (annotations.contains("Repository")) return EntityKind.REPOSITORY_COMPONENT;
            if (annotations.contains("Entity")) return EntityKind.ENTITY;
            if (annotations.contains("Configuration")) return EntityKind.CONFIGURATION;
            return EntityKind.TYPE;
        }

        private static List<String> annotationNames(List<?> modifiers) {
            return modifiers.stream().filter(Annotation.class::isInstance)
                    .map(Annotation.class::cast).map(annotation -> annotation.getTypeName().getFullyQualifiedName()).toList();
        }

        private static java.util.Optional<String> annotationArgument(List<?> modifiers, String targetName) {
            return modifiers.stream().filter(Annotation.class::isInstance).map(Annotation.class::cast)
                    .filter(annotation -> annotation.getTypeName().getFullyQualifiedName().equals(targetName)).findFirst()
                    .flatMap(annotation -> {
                        java.util.regex.Matcher value = java.util.regex.Pattern.compile("\\\"([^\\\"]+)\\\"").matcher(annotation.toString());
                        return value.find() ? java.util.Optional.of(value.group(1)) : java.util.Optional.empty();
                    });
        }

        private static String joinPaths(String base, String method) {
            String left = base == null ? "" : base.trim();
            String right = method == null ? "" : method.trim();
            if (left.isEmpty()) return right.isEmpty() ? "/" : right;
            if (right.isEmpty() || right.equals("/")) return left;
            return (left.endsWith("/") ? left.substring(0, left.length() - 1) : left)
                    + (right.startsWith("/") ? right : "/" + right);
        }
    }

    private static final class GraphBuilder {
        private final CodeGraph graph = new CodeGraph();
        private final Set<String> nodeIds = new HashSet<>();
        CodeGraph graph() { return graph; }
        void node(String id, EntityKind kind, String name, Map<String, String> attributes, Provenance provenance) {
            if (nodeIds.add(id)) graph.addNode(new GraphNode(id, kind, name, attributes, provenance));
        }
        void edge(String from, String to, RelationKind kind, Map<String, String> attributes, Provenance provenance) {
            graph.addEdge(new GraphEdge(from, to, kind, attributes, provenance));
        }
    }
}

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
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.CreationReference;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.FileASTRequestor;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.eclipse.jdt.core.dom.UnionType;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.MethodReference;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeMethodReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The deterministic Java analyzer. It deliberately preserves unresolved symbols; later JDT/SCIP
 * resolution can upgrade their provenance without discarding evidence.
 *
 * <p>Method identity includes erased parameter types, so overloads stay distinct symbols. Type
 * identity is the erased qualified name, so a generic declaration and calls into it agree.
 */
public final class JavaRepositoryAnalyzer {
    public CodeGraph analyze(Path repository) throws IOException {
        return analyze(repository, List.of(), true);
    }

    /**
     * Analyze with optional Maven/Gradle classpath entries. When entries are provided JDT
     * resolves bindings across the whole source batch; when absent, the same deterministic
     * syntax pass still runs with only the running JDK boot classpath.
     */
    public CodeGraph analyze(Path repository, List<Path> classpathEntries) throws IOException {
        return analyze(repository, classpathEntries, true);
    }

    public CodeGraph analyze(Path repository, List<Path> classpathEntries, boolean includeTests) throws IOException {
        Path root = repository.toAbsolutePath().normalize();
        CodeGraph graph = new CodeGraph();
        String repositoryName = root.getFileName() == null ? root.toString() : root.getFileName().toString();
        String repositoryId = "repo:" + repositoryName;
        // The id stays free of the local absolute path so the same commit produces the same graph
        // on every machine; the path is recorded as an attribute instead.
        graph.upsertNode(new GraphNode(repositoryId, EntityKind.REPOSITORY, repositoryName,
                Map.of("path", root.toString()), new Provenance("FILESYSTEM", 1.0, "", 0, 0)), true);
        List<Path> sourceFiles;
        try (Stream<Path> files = Files.walk(root)) {
            sourceFiles = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> includeTests || !isTestSource(root, path))
                    .map(Path::toAbsolutePath).map(Path::normalize)
                    .sorted(Comparator.comparing(Path::toString)).toList();
        }
        if (sourceFiles.isEmpty()) return graph;
        ASTParser parser = ASTParser.newParser(AST.JLS25);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        Map<String, String> compilerOptions = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.VERSION_25, compilerOptions);
        parser.setCompilerOptions(compilerOptions);
        List<Path> sourceRoots = sourceRoots(root, sourceFiles);
        String[] classpath = classpathEntries.isEmpty() ? null : classpathEntries.stream().map(path -> path.toAbsolutePath().normalize().toString()).toArray(String[]::new);
        parser.setEnvironment(classpath, sourceRoots.stream().map(Path::toString).toArray(String[]::new), null, true);
        String[] paths = sourceFiles.stream().map(Path::toString).toArray(String[]::new);
        // Units arrive in parser order, so each unit is collected into its own buffer and the
        // buffers are merged in sorted path order. The graph is then identical on every machine.
        Map<String, GraphBuilder> ordered = new LinkedHashMap<>();
        for (Path file : sourceFiles) ordered.put(file.toString(), new GraphBuilder());
        parser.createASTs(paths, null, new String[0], new FileASTRequestor() {
            @Override public void acceptAST(String sourceFilePath, CompilationUnit unit) {
                Path sourcePath = Path.of(sourceFilePath).toAbsolutePath().normalize();
                GraphBuilder buffer = ordered.get(sourcePath.toString());
                if (buffer == null) return;
                String relative = root.relativize(sourcePath).toString().replace('\\', '/');
                String fileId = "file:" + relative;
                buffer.declaration(fileId, EntityKind.FILE, relative, Map.of(), provenance(unit, 0, relative, false));
                buffer.edge(repositoryId, fileId, RelationKind.CONTAINS, Map.of(), provenance(unit, 0, relative, false));
                unit.accept(new Collector(unit, relative, fileId, buffer));
            }
        }, null);
        ordered.values().forEach(buffer -> buffer.flushInto(graph));
        new IntraRepositoryResolver().resolve(graph);
        new DispatchNormalizer().normalize(graph);
        return graph;
    }

    private static List<Path> sourceRoots(Path root, List<Path> sourceFiles) {
        Set<Path> roots = new LinkedHashSet<>();
        for (Path file : sourceFiles) {
            Path cursor = file.getParent();
            boolean foundJavaRoot = false;
            while (cursor != null && cursor.startsWith(root)) {
                if (cursor.getFileName() != null && cursor.getFileName().toString().equals("java")) {
                    roots.add(cursor);
                    foundJavaRoot = true;
                    break;
                }
                cursor = cursor.getParent();
            }
            if (!foundJavaRoot) roots.add(root);
        }
        return List.copyOf(roots);
    }

    /**
     * Recognizes the conventional test source roots only. A production package that happens to be
     * named {@code test} is production code and stays in the graph.
     */
    static boolean isTestSource(Path root, Path path) {
        String relative = root.relativize(path).toString().replace('\\', '/');
        String normalized = '/' + relative;
        return normalized.contains("/src/test/") || normalized.contains("/src/it/")
                || normalized.contains("/src/integration-test/") || normalized.contains("/src/integrationTest/")
                || normalized.contains("/src/testFixtures/");
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
        private int anonymousTypes;

        private Collector(CompilationUnit unit, String file, String fileId, GraphBuilder graph) {
            this.unit = unit;
            this.file = file;
            this.fileId = fileId;
            this.graph = graph;
        }

        @Override public boolean visit(PackageDeclaration declaration) {
            packageName = declaration.getName().getFullyQualifiedName();
            String packageId = "package:" + packageName;
            graph.declaration(packageId, EntityKind.PACKAGE, packageName, Map.of(), p(declaration.getStartPosition(), false));
            graph.edge(fileId, packageId, RelationKind.DECLARES, Map.of(), p(declaration.getStartPosition(), false));
            return true;
        }

        @Override public boolean visit(ImportDeclaration declaration) {
            String imported = declaration.getName().getFullyQualifiedName() + (declaration.isOnDemand() ? ".*" : "");
            String externalId = "external:" + imported;
            graph.reference(externalId, EntityKind.EXTERNAL_SYMBOL, imported, Map.of("reason", "import"), p(declaration.getStartPosition(), false));
            graph.edge(fileId, externalId, RelationKind.IMPORTS, Map.of("static", Boolean.toString(declaration.isStatic())), p(declaration.getStartPosition(), false));
            return false;
        }

        @Override public boolean visit(TypeDeclaration declaration) {
            List<String> annotations = annotationNames(declaration.modifiers());
            String id = enterType(declaration, declaration.isInterface() ? interfaceKind(declaration) : frameworkKind(annotations),
                    declaration.isInterface() ? "interface" : "class", annotations);
            if (declaration.getSuperclassType() != null) relateType(id, declaration.getSuperclassType(), RelationKind.EXTENDS, declaration.getStartPosition());
            for (Object implemented : declaration.superInterfaceTypes()) {
                relateType(id, (Type) implemented, declaration.isInterface() ? RelationKind.EXTENDS : RelationKind.IMPLEMENTS, declaration.getStartPosition());
            }
            return true;
        }

        @Override public void endVisit(TypeDeclaration declaration) { exitType(); }

        @Override public boolean visit(RecordDeclaration declaration) {
            List<String> annotations = annotationNames(declaration.modifiers());
            String id = enterType(declaration, frameworkKind(annotations), "record", annotations);
            for (Object implemented : declaration.superInterfaceTypes()) relateType(id, (Type) implemented, RelationKind.IMPLEMENTS, declaration.getStartPosition());
            for (Object component : declaration.recordComponents()) {
                if (!(component instanceof SingleVariableDeclaration variable)) continue;
                String name = variable.getName().getIdentifier();
                field(id, name, variable.getType(), variable.getStartPosition(), Map.of("recordComponent", "true"));
                // The accessor is implicit in source but is what callers actually invoke, so it is
                // declared here and marked synthesized rather than left to appear as a stray target.
                String accessorId = methodId(id, name, List.of());
                graph.declaration(accessorId, EntityKind.METHOD, name,
                        Map.of("arity", "0", "annotations", "", "synthesized", "record-accessor"), p(variable.getStartPosition(), false));
                graph.edge(id, accessorId, RelationKind.DECLARES, Map.of(), p(variable.getStartPosition(), false));
            }
            return true;
        }

        @Override public void endVisit(RecordDeclaration declaration) { exitType(); }

        @Override public boolean visit(EnumDeclaration declaration) {
            enterType(declaration, frameworkKind(annotationNames(declaration.modifiers())), "enum", annotationNames(declaration.modifiers()));
            return true;
        }

        @Override public void endVisit(EnumDeclaration declaration) { exitType(); }

        @Override public boolean visit(AnnotationTypeDeclaration declaration) {
            enterType(declaration, EntityKind.INTERFACE, "annotation", annotationNames(declaration.modifiers()));
            return true;
        }

        @Override public void endVisit(AnnotationTypeDeclaration declaration) { exitType(); }

        @Override public boolean visit(AnonymousClassDeclaration declaration) {
            String owner = types.isEmpty() ? fileId : types.peek();
            String id = owner + "$" + (++anonymousTypes);
            graph.declaration(id, EntityKind.TYPE, "<anonymous>", Map.of("type", "anonymous"), p(declaration.getStartPosition(), false));
            graph.edge(owner, id, RelationKind.DECLARES, Map.of(), p(declaration.getStartPosition(), false));
            types.push(id);
            typePaths.push("");
            return true;
        }

        @Override public void endVisit(AnonymousClassDeclaration declaration) { exitType(); }

        @Override public boolean visit(FieldDeclaration declaration) {
            if (types.isEmpty()) return true;
            String name = declaration.fragments().isEmpty() ? "<unnamed>" : declaration.fragments().get(0).toString().split("=")[0].trim();
            Map<String, String> attributes = new java.util.LinkedHashMap<>(annotationAttributes(declaration.modifiers()));
            attributes.put("annotations", String.join(",", annotationNames(declaration.modifiers())));
            int at = declaration.fragments().isEmpty() ? declaration.getStartPosition()
                    : ((org.eclipse.jdt.core.dom.VariableDeclarationFragment) declaration.fragments().get(0)).getName().getStartPosition();
            field(types.peek(), name, declaration.getType(), at, Map.copyOf(attributes));
            return true;
        }

        @Override public boolean visit(MethodDeclaration declaration) {
            if (types.isEmpty()) {
                methods.push("");
                return true;
            }
            String name = declaration.isConstructor() ? "<init>" : declaration.getName().getIdentifier();
            String methodId = methodId(types.peek(), name, parameterTypes(declaration));
            int at = declaration.getName().getStartPosition();
            List<String> annotations = annotationNames(declaration.modifiers());
            Map<String, String> attributes = new java.util.LinkedHashMap<>();
            attributes.put("arity", Integer.toString(declaration.parameters().size()));
            attributes.put("annotations", String.join(",", annotations));
            attributes.putAll(annotationAttributes(declaration.modifiers()));
            graph.declaration(methodId, EntityKind.METHOD, name, Map.copyOf(attributes), p(at, false));
            graph.edge(types.peek(), methodId, RelationKind.DECLARES, Map.of(), p(at, false));
            for (Object parameter : declaration.parameters()) {
                if (parameter instanceof SingleVariableDeclaration variable) {
                    dependency(types.peek(), variable.getType().resolveBinding(), variable.getStartPosition());
                    injection(methodId, variable, annotations, declaration.isConstructor());
                }
            }
            for (Object thrown : declaration.thrownExceptionTypes()) {
                exception(((Type) thrown).resolveBinding(), RelationKind.THROWS, declaration.getStartPosition(), "throws-clause", methodId);
            }
            overrides(declaration, methodId, at);
            endpoint(declaration, annotations, methodId, at);
            kafkaListener(declaration, annotations, methodId, at);
            if (annotations.contains("Transactional")) {
                String transactionId = "transaction:" + methodId;
                graph.declaration(transactionId, EntityKind.TRANSACTION, name + " transaction", Map.of(), p(at, false));
                graph.edge(methodId, transactionId, RelationKind.PARTICIPATES_IN, Map.of(), p(at, false));
            }
            methods.push(methodId);
            return true;
        }

        @Override public void endVisit(MethodDeclaration declaration) { if (!methods.isEmpty()) methods.pop(); }

        @Override public boolean visit(MethodInvocation invocation) {
            call(invocation.resolveMethodBinding(), invocation.getStartPosition(), literalArguments(invocation.arguments()),
                    () -> (invocation.getExpression() == null ? "" : invocation.getExpression() + ".") + invocation.getName().getIdentifier(),
                    () -> invocation.getExpression() == null ? "" : invocation.getExpression().toString(),
                    invocation.getName().getIdentifier(), invocation.arguments().size());
            return true;
        }

        @Override public boolean visit(SuperMethodInvocation invocation) {
            call(invocation.resolveMethodBinding(), invocation.getStartPosition(), Map.of("via", "super"),
                    () -> "super." + invocation.getName().getIdentifier(), () -> "super",
                    invocation.getName().getIdentifier(), invocation.arguments().size());
            return true;
        }

        @Override public boolean visit(Assignment assignment) {
            fieldAccess(assignment.getLeftHandSide(), RelationKind.WRITES);
            return true;
        }

        @Override public boolean visit(PrefixExpression expression) {
            if (expression.getOperator() == PrefixExpression.Operator.INCREMENT || expression.getOperator() == PrefixExpression.Operator.DECREMENT) {
                fieldAccess(expression.getOperand(), RelationKind.WRITES);
            }
            return true;
        }

        @Override public boolean visit(PostfixExpression expression) {
            fieldAccess(expression.getOperand(), RelationKind.WRITES);
            return true;
        }

        @Override public boolean visit(SimpleName name) {
            if (isAssignmentTarget(name)) return true;
            fieldAccess(name, RelationKind.READS);
            return true;
        }

        @Override public boolean visit(FieldAccess access) {
            if (!isAssignmentTarget(access)) fieldAccess(access, RelationKind.READS);
            return true;
        }

        @Override public boolean visit(ThrowStatement statement) {
            ITypeBinding thrown = statement.getExpression() == null ? null : statement.getExpression().resolveTypeBinding();
            exception(thrown, RelationKind.THROWS, statement.getStartPosition(), "throw");
            return true;
        }

        @Override public boolean visit(CatchClause clause) {
            Type caught = clause.getException().getType();
            if (caught instanceof UnionType union) {
                for (Object alternative : union.types()) {
                    exception(((Type) alternative).resolveBinding(), RelationKind.CATCHES, clause.getStartPosition(), "catch");
                }
                return true;
            }
            exception(caught.resolveBinding(), RelationKind.CATCHES, clause.getStartPosition(), "catch");
            return true;
        }

        @Override public boolean visit(ExpressionMethodReference reference) { return methodReference(reference, reference.getName().getIdentifier()); }

        @Override public boolean visit(TypeMethodReference reference) { return methodReference(reference, reference.getName().getIdentifier()); }

        @Override public boolean visit(SuperMethodReference reference) { return methodReference(reference, reference.getName().getIdentifier()); }

        @Override public boolean visit(CreationReference reference) { return methodReference(reference, "<init>"); }

        @Override public boolean visit(ClassInstanceCreation creation) {
            if (currentMethod() == null) return true;
            int position = creation.getStartPosition();
            ITypeBinding created = creation.getType().resolveBinding();
            if (created != null && !created.isRecovered() && qualifiedName(created) != null) {
                String typeId = "type:" + qualifiedName(created);
                graph.reference(typeId, EntityKind.EXTERNAL_SYMBOL, qualifiedName(created), Map.of("reason", "instantiated-type"), p(position, false));
                graph.edge(currentMethod(), typeId, RelationKind.CREATES,
                        Map.of("resolution", "JDT_BINDING"), binding(position));
                IMethodBinding constructor = creation.resolveConstructorBinding();
                if (constructor != null && !constructor.isRecovered()) {
                    call(constructor, position, Map.of("via", "constructor"), () -> "new " + created.getName(), created::getName, "<init>", creation.arguments().size());
                }
                return true;
            }
            String target = creation.getType().toString();
            String targetId = "external:type:" + target;
            graph.reference(targetId, EntityKind.EXTERNAL_SYMBOL, target, Map.of("reason", "unresolved-instantiation"), p(position, true));
            graph.edge(currentMethod(), targetId, RelationKind.CREATES, Map.of(), p(position, true));
            return true;
        }

        /** Records a read or write of a field that some type in the graph declares. */
        private void fieldAccess(Expression expression, RelationKind kind) {
            String caller = currentMethod();
            if (caller == null) return;
            IVariableBinding variable = switch (expression) {
                case SimpleName name -> name.resolveBinding() instanceof IVariableBinding binding ? binding : null;
                case FieldAccess access -> access.resolveFieldBinding();
                case QualifiedName name -> name.resolveBinding() instanceof IVariableBinding binding ? binding : null;
                default -> null;
            };
            if (variable == null || !variable.isField() || variable.getDeclaringClass() == null) return;
            String owner = qualifiedName(variable.getDeclaringClass());
            if (owner == null) return;
            String fieldId = "type:" + owner + ".field:" + variable.getName();
            graph.reference(fieldId, EntityKind.FIELD, variable.getName(),
                    Map.of("reason", kind == RelationKind.READS ? "read-field" : "written-field"), p(expression.getStartPosition(), false));
            graph.edge(caller, fieldId, kind, Map.of("resolution", "JDT_BINDING"), binding(expression.getStartPosition()));
        }

        private static boolean isAssignmentTarget(Expression expression) {
            return expression.getParent() instanceof Assignment assignment && assignment.getLeftHandSide() == expression;
        }

        private void exception(ITypeBinding type, RelationKind kind, int position, String reason) {
            exception(type, kind, position, reason, currentMethod());
        }

        private void exception(ITypeBinding type, RelationKind kind, int position, String reason, String caller) {
            if (caller == null || type == null || type.isRecovered()) return;
            String qualified = qualifiedName(type);
            if (qualified == null) return;
            String typeId = "type:" + qualified;
            graph.reference(typeId, EntityKind.EXTERNAL_SYMBOL, qualified, Map.of("reason", reason), p(position, false));
            graph.edge(caller, typeId, kind, Map.of("resolution", "JDT_BINDING"), binding(position));
        }

        /** Links a declaration to every supertype method it overrides or implements. */
        private void overrides(MethodDeclaration declaration, String declaredId, int at) {
            IMethodBinding binding = declaration.resolveBinding();
            if (binding == null || binding.isConstructor() || binding.getDeclaringClass() == null) return;
            for (ITypeBinding supertype : supertypes(binding.getDeclaringClass())) {
                for (IMethodBinding candidate : supertype.getDeclaredMethods()) {
                    if (!binding.overrides(candidate)) continue;
                    String owner = qualifiedName(supertype);
                    if (owner == null) continue;
                    IMethodBinding overridden = candidate.getMethodDeclaration();
                    String targetId = methodId("type:" + owner, overridden.getName(), erasedNames(overridden.getParameterTypes()));
                    graph.reference(targetId, EntityKind.METHOD, overridden.getName(),
                            Map.of("resolved", "true", "arity", Integer.toString(overridden.getParameterTypes().length)),
                            p(at, false));
                    graph.edge(declaredId, targetId, RelationKind.OVERRIDES,
                            Map.of("resolution", "JDT_BINDING", "declaringKind", supertype.isInterface() ? "interface" : "class"),
                            binding(at));
                }
            }
        }

        private static List<ITypeBinding> supertypes(ITypeBinding type) {
            List<ITypeBinding> found = new ArrayList<>();
            ArrayDeque<ITypeBinding> queue = new ArrayDeque<>();
            if (type.getSuperclass() != null) queue.add(type.getSuperclass());
            queue.addAll(List.of(type.getInterfaces()));
            java.util.Set<String> seen = new java.util.HashSet<>();
            while (!queue.isEmpty()) {
                ITypeBinding current = queue.remove();
                if (current == null || !seen.add(current.getKey())) continue;
                found.add(current);
                if (current.getSuperclass() != null) queue.add(current.getSuperclass());
                queue.addAll(List.of(current.getInterfaces()));
            }
            return found;
        }

        private boolean methodReference(MethodReference reference, String name) {
            call(reference.resolveMethodBinding(), reference.getStartPosition(), Map.of("via", "method-reference"),
                    reference::toString, () -> "", name, -1);
            return true;
        }

        /**
         * Records one call. A resolved binding produces an edge to the declared method's canonical
         * id; anything else produces an explicitly unresolved edge that carries the receiver text
         * and arity as attributes, so later resolvers never have to parse an id back apart.
         */
        private void call(IMethodBinding binding, int position, Map<String, String> attributes,
                          java.util.function.Supplier<String> unresolvedName, java.util.function.Supplier<String> receiver,
                          String methodName, int arity) {
            String caller = currentMethod();
            if (caller == null) return;
            if (binding != null && !binding.isRecovered() && binding.getMethodDeclaration() != null
                    && binding.getMethodDeclaration().getDeclaringClass() != null) {
                IMethodBinding declaration = binding.getMethodDeclaration();
                String owner = qualifiedName(declaration.getDeclaringClass());
                if (owner != null) {
                    String name = declaration.isConstructor() ? "<init>" : declaration.getName();
                    String targetId = methodId("type:" + owner, name, erasedNames(declaration.getParameterTypes()));
                    graph.reference(targetId, EntityKind.METHOD, name,
                            Map.of("bindingKey", declaration.getKey(), "resolved", "true",
                                    "arity", Integer.toString(declaration.getParameterTypes().length)), p(position, false));
                    Map<String, String> edgeAttributes = new java.util.LinkedHashMap<>(attributes);
                    edgeAttributes.put("resolution", "JDT_BINDING");
                    edgeAttributes.put("bindingKey", declaration.getKey());
                    graph.edge(caller, targetId, RelationKind.CALLS, Map.copyOf(edgeAttributes), binding(position));
                    return;
                }
            }
            String target = unresolvedName.get() + "/" + (arity < 0 ? "?" : Integer.toString(arity));
            String targetId = "external:call:" + target;
            Map<String, String> nodeAttributes = new java.util.LinkedHashMap<>();
            nodeAttributes.put("reason", "unresolved-call");
            nodeAttributes.put("receiver", receiver.get());
            nodeAttributes.put("method", methodName);
            nodeAttributes.put("arity", arity < 0 ? "?" : Integer.toString(arity));
            graph.reference(targetId, EntityKind.EXTERNAL_SYMBOL, target, Map.copyOf(nodeAttributes), p(position, true));
            graph.edge(caller, targetId, RelationKind.CALLS, attributes, p(position, true));
        }

        private String enterType(AbstractTypeDeclaration declaration, EntityKind kind, String flavour, List<String> annotations) {
            String name = qualified(declaration.getName().getIdentifier());
            String id = "type:" + name;
            // A declaration's start position is the start of its Javadoc. Every consumer of this -
            // a SARIF location, an IDE jump, an enrichment packet - wants the line the name is on,
            // which is also the line a reader would cite.
            int at = declaration.getName().getStartPosition();
            Map<String, String> attributes = new java.util.LinkedHashMap<>();
            attributes.put("annotations", String.join(",", annotations));
            attributes.put("type", flavour);
            attributes.putAll(annotationAttributes(declaration.modifiers()));
            graph.declaration(id, kind, name, Map.copyOf(attributes), p(at, false));
            graph.edge(types.isEmpty() ? fileId : types.peek(), id, RelationKind.DECLARES, Map.of(), p(at, false));
            types.push(id);
            if (annotations.contains("Entity")) {
                // JPA's @Table names the table with `name`; `value` is not a member of it. Falling
                // back to the type's simple name is a guess, and is marked as inferred below.
                String table = annotationArgument(declaration.modifiers(), "Table", "name")
                        .or(() -> annotationArgument(declaration.modifiers(), "Table"))
                        .or(() -> annotationArgument(declaration.modifiers(), "Entity", "name"))
                        .orElse(declaration.getName().getIdentifier());
                String tableId = "table:" + table;
                graph.declaration(tableId, EntityKind.DATABASE_TABLE, table, Map.of("inferred", Boolean.toString(!annotations.contains("Table"))), p(at, false));
                graph.edge(id, tableId, RelationKind.PERSISTS, Map.of(), p(at, false));
            }
            typePaths.push(annotationArgument(declaration.modifiers(), "RequestMapping").orElse(""));
            return id;
        }

        private void exitType() {
            if (!types.isEmpty()) types.pop();
            if (!typePaths.isEmpty()) typePaths.pop();
        }

        /**
         * Records a constructor or setter parameter as a candidate injection point. Whether the
         * container actually injects it is a framework question, decided later from these facts.
         */
        private void injection(String methodId, SingleVariableDeclaration variable, List<String> methodAnnotations, boolean constructor) {
            boolean setter = methodAnnotations.contains("Autowired") || methodAnnotations.contains("Inject");
            if (!constructor && !setter) return;
            ITypeBinding type = variable.getType().resolveBinding();
            if (type == null || type.isRecovered() || type.isPrimitive()) return;
            String qualified = qualifiedName(type);
            if (qualified == null) return;
            String typeId = "type:" + qualified;
            graph.reference(typeId, EntityKind.EXTERNAL_SYMBOL, qualified, Map.of("reason", "injection-point"), p(variable.getStartPosition(), false));
            graph.edge(methodId, typeId, RelationKind.DEPENDS_ON,
                    Map.of("resolution", "JDT_BINDING", "injection", constructor ? "constructor" : "setter", "parameter", variable.getName().getIdentifier()),
                    binding(variable.getStartPosition()));
        }

        private void field(String ownerId, String name, Type type, int position, Map<String, String> extra) {
            String id = ownerId + ".field:" + name;
            Map<String, String> attributes = new java.util.LinkedHashMap<>(extra);
            attributes.put("declaredType", type.toString());
            graph.declaration(id, EntityKind.FIELD, name, Map.copyOf(attributes), p(position, false));
            graph.edge(ownerId, id, RelationKind.DECLARES, Map.of(), p(position, false));
            dependency(ownerId, type.resolveBinding(), position);
        }

        /** The innermost enclosing method, or null inside an initializer or a skipped context. */
        private String currentMethod() {
            String current = methods.peek();
            return current == null || current.isEmpty() ? null : current;
        }

        private void relateType(String from, Type source, RelationKind kind, int position) {
            ITypeBinding binding = source.resolveBinding();
            String qualified = binding == null || binding.isRecovered() ? null : qualifiedName(binding);
            if (qualified != null) {
                String targetId = "type:" + qualified;
                graph.reference(targetId, EntityKind.EXTERNAL_SYMBOL, qualified, Map.of("reason", "supertype"), p(position, false));
                graph.edge(from, targetId, kind, Map.of("resolution", "JDT_BINDING"), binding(position));
                return;
            }
            String target = source.toString();
            String targetId = "external:type:" + target;
            graph.reference(targetId, EntityKind.EXTERNAL_SYMBOL, target, Map.of("reason", "unresolved-type"), p(position, true));
            graph.edge(from, targetId, kind, Map.of(), p(position, true));
        }

        private String qualified(String localName) {
            String owner = types.isEmpty() ? packageName : types.peek().substring("type:".length());
            return owner.isBlank() ? localName : owner + "." + localName;
        }

        private Provenance p(int position, boolean unresolved) { return provenance(unit, position, file, unresolved); }

        private Provenance binding(int position) {
            return new Provenance("JDT_BINDING", 1.0, file, unit.getLineNumber(position), unit.getColumnNumber(position) + 1);
        }

        private List<String> parameterTypes(MethodDeclaration declaration) {
            IMethodBinding binding = declaration.resolveBinding();
            if (binding != null && binding.getParameterTypes().length == declaration.parameters().size()) {
                List<String> resolved = erasedNames(binding.getParameterTypes());
                if (resolved.stream().noneMatch(String::isBlank)) return resolved;
            }
            List<String> written = new ArrayList<>();
            for (Object parameter : declaration.parameters()) {
                written.add(parameter instanceof SingleVariableDeclaration variable ? variable.getType().toString() : "?");
            }
            return List.copyOf(written);
        }

        private void endpoint(MethodDeclaration declaration, List<String> annotations, String methodId, int at) {
            String mapping = annotations.stream().filter(name -> name.endsWith("Mapping")).findFirst().orElse(null);
            if (mapping == null) return;
            String verb = switch (mapping) {
                case "GetMapping" -> "GET";
                case "PostMapping" -> "POST";
                case "PutMapping" -> "PUT";
                case "DeleteMapping" -> "DELETE";
                case "PatchMapping" -> "PATCH";
                default -> annotationArgument(declaration.modifiers(), mapping, "method")
                        .map(value -> value.substring(value.lastIndexOf('.') + 1)).orElse("REQUEST");
            };
            String path = joinPaths(typePaths.peek(), annotationArgument(declaration.modifiers(), mapping).orElse("/"));
            String endpointId = "endpoint:" + verb + ":" + path;
            graph.declaration(endpointId, EntityKind.ENDPOINT, verb + " " + path, Map.of("httpMethod", verb, "path", path), p(at, false));
            graph.edge(endpointId, methodId, RelationKind.EXPOSES, Map.of(), p(at, false));
        }

        private void kafkaListener(MethodDeclaration declaration, List<String> annotations, String methodId, int at) {
            if (!annotations.contains("KafkaListener")) return;
            String topic = annotationArgument(declaration.modifiers(), "KafkaListener").orElse("<dynamic-topic>");
            String topicId = "topic:" + topic;
            graph.declaration(topicId, EntityKind.TOPIC, topic, Map.of(), p(at, false));
            graph.edge(methodId, topicId, RelationKind.CONSUMES, Map.of(), p(at, false));
        }

        private static EntityKind frameworkKind(List<String> annotations) {
            if (annotations.contains("RestController") || annotations.contains("Controller")) return EntityKind.CONTROLLER;
            if (annotations.contains("Service")) return EntityKind.SERVICE;
            if (annotations.contains("Repository")) return EntityKind.REPOSITORY_COMPONENT;
            if (annotations.contains("Entity")) return EntityKind.ENTITY;
            if (annotations.contains("Configuration")) return EntityKind.CONFIGURATION;
            return EntityKind.TYPE;
        }

        private static EntityKind interfaceKind(TypeDeclaration declaration) {
            boolean repository = declaration.superInterfaceTypes().stream()
                    .anyMatch(value -> value.toString().endsWith("Repository") || value.toString().contains("Repository<"));
            return repository ? EntityKind.REPOSITORY_COMPONENT : EntityKind.INTERFACE;
        }

        private void dependency(String ownerId, ITypeBinding binding, int position) {
            if (binding == null || binding.isRecovered() || binding.isPrimitive()) return;
            String qualified = qualifiedName(binding);
            if (qualified == null) return;
            String targetId = "type:" + qualified;
            graph.reference(targetId, EntityKind.EXTERNAL_SYMBOL, qualified, Map.of("reason", "classpath-type"), p(position, false));
            graph.edge(ownerId, targetId, RelationKind.DEPENDS_ON, Map.of("resolution", "JDT_BINDING", "bindingKey", binding.getErasure().getKey()), binding(position));
        }

        /**
         * Captures every constant-valued annotation member as {@code annotation.<Name>.<member>}.
         * The analyzer records what the source says; interpreting {@code @Query} or
         * {@code @PreAuthorize} belongs to the framework layer, not here.
         */
        private static Map<String, String> annotationAttributes(List<?> modifiers) {
            Map<String, String> attributes = new java.util.LinkedHashMap<>();
            for (Object modifier : modifiers) {
                if (!(modifier instanceof Annotation annotation)) continue;
                String name = annotation.getTypeName().getFullyQualifiedName();
                if (annotation instanceof SingleMemberAnnotation single) {
                    literal(single.getValue()).ifPresent(value -> attributes.put("annotation." + name + ".value", value));
                }
                if (annotation instanceof NormalAnnotation normal) {
                    for (Object member : normal.values()) {
                        if (!(member instanceof MemberValuePair pair)) continue;
                        literal(pair.getValue()).ifPresent(value ->
                                attributes.put("annotation." + name + "." + pair.getName().getIdentifier(), value));
                    }
                }
                attributes.putIfAbsent("annotation." + name, "");
            }
            return Map.copyOf(attributes);
        }

        /**
         * Records constant arguments as {@code arg0..argN}. A topic name or a URL passed as a
         * literal is a deterministic fact about the call site, and the framework layer needs it.
         */
        private static Map<String, String> literalArguments(List<?> arguments) {
            Map<String, String> captured = new java.util.LinkedHashMap<>();
            for (int i = 0; i < Math.min(arguments.size(), 3); i++) {
                if (!(arguments.get(i) instanceof Expression expression)) continue;
                if (!(expression instanceof StringLiteral) && expression.resolveConstantExpressionValue() == null) continue;
                int position = i;
                literal(expression).ifPresent(value -> captured.put("arg" + position, value));
            }
            return Map.copyOf(captured);
        }

        private static Optional<String> literal(Expression expression) {
            Expression value = expression;
            if (value instanceof ArrayInitializer array) {
                List<String> parts = new ArrayList<>();
                for (Object element : array.expressions()) literal((Expression) element).ifPresent(parts::add);
                return parts.isEmpty() ? Optional.empty() : Optional.of(String.join(",", parts));
            }
            if (value instanceof StringLiteral literal) return Optional.of(literal.getLiteralValue());
            Object constant = value.resolveConstantExpressionValue();
            if (constant != null) return Optional.of(constant.toString());
            return Optional.of(value.toString());
        }

        private static List<String> annotationNames(List<?> modifiers) {
            return modifiers.stream().filter(Annotation.class::isInstance)
                    .map(Annotation.class::cast).map(annotation -> annotation.getTypeName().getFullyQualifiedName()).toList();
        }

        private static Optional<String> annotationArgument(List<?> modifiers, String targetName) {
            return annotationArgument(modifiers, targetName, "value");
        }

        private static Optional<String> annotationArgument(List<?> modifiers, String targetName, String memberName) {
            return modifiers.stream().filter(Annotation.class::isInstance).map(Annotation.class::cast)
                    .filter(annotation -> annotation.getTypeName().getFullyQualifiedName().equals(targetName)).findFirst()
                    .flatMap(annotation -> annotationMember(annotation, memberName));
        }

        private static Optional<String> annotationMember(Annotation annotation, String memberName) {
            Expression value = null;
            if (annotation instanceof SingleMemberAnnotation single && memberName.equals("value")) value = single.getValue();
            if (annotation instanceof NormalAnnotation normal) {
                for (Object member : normal.values()) {
                    if (member instanceof MemberValuePair pair && pair.getName().getIdentifier().equals(memberName)) {
                        value = pair.getValue();
                        break;
                    }
                }
            }
            if (value == null) return Optional.empty();
            if (value instanceof ArrayInitializer array && !array.expressions().isEmpty()) value = (Expression) array.expressions().get(0);
            if (value instanceof StringLiteral literal) return Optional.of(literal.getLiteralValue());
            Object constant = value.resolveConstantExpressionValue();
            if (constant instanceof String text) return Optional.of(text);
            String text = value.toString();
            if (memberName.equals("method") && text.contains(".")) return Optional.of(text);
            return Optional.empty();
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

    /** Canonical method identity: owning type, name, and erased parameter types. */
    static String methodId(String ownerId, String name, List<String> parameterTypes) {
        return ownerId + "#" + name + "(" + String.join(",", parameterTypes) + ")";
    }

    private static List<String> erasedNames(ITypeBinding[] bindings) {
        List<String> names = new ArrayList<>(bindings.length);
        for (ITypeBinding binding : bindings) {
            String qualified = qualifiedName(binding);
            names.add(qualified == null ? binding.getName() : qualified);
        }
        return List.copyOf(names);
    }

    /** The erased qualified name, or null when JDT cannot name the type. */
    private static String qualifiedName(ITypeBinding binding) {
        ITypeBinding erasure = binding.getErasure() == null ? binding : binding.getErasure();
        String qualified = erasure.getQualifiedName();
        if (qualified == null || qualified.isBlank() || qualified.equals("<null>")) return null;
        return qualified;
    }

    /**
     * Buffers one compilation unit's symbols so they can be merged in sorted path order rather
     * than in whichever order the parser happens to deliver units.
     */
    private static final class GraphBuilder {
        private final List<GraphNode> declarations = new ArrayList<>();
        private final List<GraphNode> references = new ArrayList<>();
        private final List<GraphEdge> pending = new ArrayList<>();

        /** A symbol recorded at its own definition site; it owns the node's provenance. */
        void declaration(String id, EntityKind kind, String name, Map<String, String> attributes, Provenance provenance) {
            declarations.add(new GraphNode(id, kind, name, attributes, provenance));
        }

        /** A symbol recorded because something referred to it; it never overwrites a declaration. */
        void reference(String id, EntityKind kind, String name, Map<String, String> attributes, Provenance provenance) {
            references.add(new GraphNode(id, kind, name, attributes, provenance));
        }

        void edge(String from, String to, RelationKind kind, Map<String, String> attributes, Provenance provenance) {
            pending.add(new GraphEdge(from, to, kind, attributes, provenance));
        }

        void flushInto(CodeGraph graph) {
            references.forEach(graph::upsertNode);
            declarations.forEach(node -> graph.upsertNode(node, true));
            pending.forEach(graph::addEdge);
        }
    }
}

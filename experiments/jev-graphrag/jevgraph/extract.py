"""Tree-sitter pass: parse every Java file and keep what syntax alone can prove.

Nothing here asks a model. What comes out is (a) the raw AST stream the UI draws as noise, (b) the
declarations and syntax facts that go straight into the graph, and (c) candidate pairs - "type A
mentions type B here, here and here" - which are the only places a model is later asked to judge.
"""

from __future__ import annotations

import re
import time
from dataclasses import dataclass, field
from pathlib import Path

import tree_sitter_java as tsj
from tree_sitter import Language, Node, Parser

JAVA = Language(tsj.language())

TYPE_DECLARATIONS = {
    "class_declaration": "class",
    "interface_declaration": "interface",
    "enum_declaration": "enum",
    "record_declaration": "record",
    "annotation_type_declaration": "annotation",
}
MAPPING_ANNOTATIONS = {
    "GetMapping": "GET", "PostMapping": "POST", "PutMapping": "PUT",
    "DeleteMapping": "DELETE", "PatchMapping": "PATCH", "RequestMapping": "ANY",
}
# Particle categories the UI colours by. Order matters: the UI indexes a palette with these numbers.
CATEGORIES = ["declaration", "expression", "identifier", "literal", "type", "comment", "token", "other"]


@dataclass
class Annotation:
    name: str
    args: str  # raw argument text, e.g. '"/billing"' or 'topics = "payment-authorized"'


@dataclass
class FieldDecl:
    name: str
    type: str
    type_names: list[str]
    annotations: list[Annotation]
    line: int
    creates: str | None  # the type after `new`, when the initializer is a constructor call


@dataclass
class Invocation:
    receiver: str | None
    receiver_type: str | None  # simple type name, when the receiver resolves to a field, parameter or local
    method: str
    line: int


@dataclass
class MethodDecl:
    name: str
    params: list[tuple[str, str]]
    returns: str
    type_names: list[str]  # every type named in the signature or the body's local declarations
    param_types: list[str]  # each parameter's erased type: List<Order> is List
    annotations: list[Annotation]
    line: int
    invocations: list[Invocation]
    creations: list[tuple[str, int]]


@dataclass
class TypeDecl:
    name: str
    qualified: str
    kind: str
    package: str
    file: str
    line: int
    end_line: int
    annotations: list[Annotation]
    extends: list[str]
    implements: list[str]
    fields: list[FieldDecl]
    methods: list[MethodDecl]
    source: str
    imports: list[str] = field(default_factory=list)

    @property
    def id(self) -> str:
        return "type:" + self.qualified


@dataclass
class FileParse:
    path: str
    package: str
    imports: list[str]
    types: list[TypeDecl]
    node_count: int
    categories: list[int]
    parse_ms: float
    lines: int


@dataclass
class Evidence:
    kind: str  # field_type | parameter_type | type_argument | mentions_type | field_new | creation | invocation
    how: str
    line: int
    code: str
    method: str | None = None  # the invoked method's name, for call evidence


# Which syntax edge each kind of evidence proves. CALLS additionally needs the invoked method to be
# one the target itself declares, which `syntax_kinds` checks.
# DEPENDS_ON follows the product's Java analyzer exactly: the erased type of a field or a parameter.
# Generic arguments, return types and locals are evidence for the judge to read, not edges.
EDGE_FOR = {"field_type": "DEPENDS_ON", "parameter_type": "DEPENDS_ON", "field_new": "CREATES", "creation": "CREATES",
            "type_argument": None, "mentions_type": None}


@dataclass
class Candidate:
    source: str  # type id
    target: str  # type id
    evidence: list[Evidence]


def category(node: Node) -> int:
    kind = node.type
    if not node.is_named:
        return CATEGORIES.index("token")
    if "comment" in kind:
        return CATEGORIES.index("comment")
    if "declaration" in kind or kind in ("variable_declarator", "formal_parameter", "modifiers"):
        return CATEGORIES.index("declaration")
    if "literal" in kind or kind in ("string_fragment", "true", "false", "null_literal"):
        return CATEGORIES.index("literal")
    if kind in ("type_identifier", "generic_type", "scoped_type_identifier", "integral_type", "void_type"):
        return CATEGORIES.index("type")
    if "identifier" in kind:
        return CATEGORIES.index("identifier")
    if "expression" in kind or "invocation" in kind or "statement" in kind or kind in ("block", "argument_list"):
        return CATEGORIES.index("expression")
    return CATEGORIES.index("other")


def text(node: Node | None) -> str:
    return node.text.decode("utf-8", "replace") if node is not None else ""


def walk(node: Node):
    cursor = node.walk()
    visited_children = False
    while True:
        if not visited_children:
            yield cursor.node
            if cursor.goto_first_child():
                continue
        if cursor.goto_next_sibling():
            visited_children = False
        elif cursor.goto_parent():
            visited_children = True
        else:
            return


def type_names(node: Node | None) -> list[str]:
    """Every simple type name inside a type expression, so List<Order> yields List and Order."""
    if node is None:
        return []
    if node.type == "type_identifier":
        return [text(node)]
    names = []
    for child in walk(node):
        if child.type == "type_identifier":
            names.append(text(child))
    return names


def annotations_of(declaration: Node) -> list[Annotation]:
    found = []
    for child in declaration.children:
        if child.type != "modifiers":
            continue
        for modifier in child.children:
            if modifier.type == "marker_annotation":
                found.append(Annotation(text(modifier.child_by_field_name("name")), ""))
            elif modifier.type == "annotation":
                arguments = text(modifier.child_by_field_name("arguments"))
                found.append(Annotation(text(modifier.child_by_field_name("name")), arguments[1:-1].strip()))
    return found


def _locals(body: Node | None) -> dict[str, str]:
    """Every variable a method body declares with a type syntax can see: locals (including `var x =
    new T()`), enhanced-for variables, catch parameters and try-with-resources."""
    names = {}
    if body is None:
        return names
    for node in walk(body):
        if node.type in ("local_variable_declaration", "resource"):
            declared = type_names(node.child_by_field_name("type"))
            declarators = node.children_by_field_name("declarator") or [node]
            for declarator in declarators:
                kind = declared[0] if declared else None
                value = declarator.child_by_field_name("value")
                if kind == "var" and value is not None and value.type == "object_creation_expression":
                    created = type_names(value.child_by_field_name("type"))
                    kind = created[0] if created else None
                if kind and kind != "var":
                    names[text(declarator.child_by_field_name("name"))] = kind
        elif node.type == "enhanced_for_statement":
            declared = type_names(node.child_by_field_name("type"))
            if declared and declared[0] != "var":
                names[text(node.child_by_field_name("name"))] = declared[0]
        elif node.type == "catch_formal_parameter":
            caught = [c for c in node.children if c.type == "catch_type"]
            declared = type_names(caught[0]) if caught else []
            if len(declared) == 1:  # a multi-catch has no single type
                names[text(node.child_by_field_name("name"))] = declared[0]
    return names


def _method(node: Node, field_types: dict[str, str]) -> MethodDecl:
    params = []
    param_types: list[str] = []
    signature_types = type_names(node.child_by_field_name("type"))
    parameters = node.child_by_field_name("parameters")
    if parameters is not None:
        for parameter in parameters.named_children:
            if parameter.type in ("formal_parameter", "spread_parameter"):
                parameter_type = parameter.child_by_field_name("type")
                params.append((text(parameter_type), text(parameter.child_by_field_name("name"))))
                names = type_names(parameter_type)
                signature_types += names
                if names:
                    param_types.append(names[0])
    body = node.child_by_field_name("body")
    local_types = _locals(body)
    scope = {**field_types, **{name: (type_names_from_text(kind) or [kind])[0] for kind, name in params}, **local_types}
    invocations, creations = [], []
    if body is not None:
        for inner in walk(body):
            if inner.type == "method_invocation":
                receiver_node = inner.child_by_field_name("object")
                receiver = text(receiver_node) if receiver_node is not None else None
                receiver_type = None
                if receiver_node is not None and receiver_node.type in ("this", "super"):
                    receiver = None  # a call on the class itself or its parents, like an unqualified call
                elif receiver_node is not None and receiver_node.type == "identifier":
                    receiver_type = scope.get(receiver) or (receiver if receiver[:1].isupper() else None)
                elif receiver_node is not None and receiver_node.type == "field_access":
                    field_text = text(receiver_node)
                    if field_text.startswith("this."):
                        receiver_type = scope.get(text(receiver_node.child_by_field_name("field")))
                    elif all(part[:1].isupper() for part in field_text.split(".")):
                        receiver_type = field_text  # Outer.Inner.staticMethod()
                elif receiver_node is not None and receiver_node.type == "object_creation_expression":
                    created = type_names(receiver_node.child_by_field_name("type"))
                    receiver_type = created[0] if created else None  # new Gate().evaluate()
                invocations.append(Invocation(receiver, receiver_type, text(inner.child_by_field_name("name")),
                                              inner.start_point[0] + 1))
            elif inner.type == "object_creation_expression":
                created = type_names(inner.child_by_field_name("type"))
                if created:
                    creations.append((created[0], inner.start_point[0] + 1))
    return MethodDecl(
        name=text(node.child_by_field_name("name")),
        params=params,
        returns=text(node.child_by_field_name("type")) or ("<init>" if node.type == "constructor_declaration" else ""),
        type_names=sorted(set(signature_types + list(local_types.values()))),
        param_types=param_types,
        annotations=annotations_of(node),
        line=node.start_point[0] + 1,
        invocations=invocations,
        creations=creations,
    )


def type_names_from_text(type_text: str) -> list[str]:
    return re.findall(r"[A-Z][A-Za-z0-9_]*", type_text)


def _type(node: Node, package: str, outer: str | None, path: str, source: bytes, imports: list[str]) -> list[TypeDecl]:
    name = text(node.child_by_field_name("name"))
    qualified = (outer + "." if outer else (package + "." if package else "")) + name
    extends, implements = [], []
    superclass = node.child_by_field_name("superclass")
    if superclass is not None:
        extends = type_names(superclass)
    interfaces = node.child_by_field_name("interfaces")
    if interfaces is not None:
        implements = type_names(interfaces)
    for child in node.children:  # interfaces extend with `extends_interfaces`
        if child.type == "extends_interfaces":
            extends += type_names(child)

    fields: list[FieldDecl] = []
    components: list[MethodDecl] = []
    if node.type == "record_declaration":  # each component is a field with an implicit accessor
        for component in (node.child_by_field_name("parameters") or node).named_children:
            if component.type == "formal_parameter":
                kind = component.child_by_field_name("type")
                name_text = text(component.child_by_field_name("name"))
                fields.append(FieldDecl(name_text, text(kind), type_names(kind), [], component.start_point[0] + 1, None))
                components.append(MethodDecl(name_text, [], text(kind), [], [], [], component.start_point[0] + 1, [], []))
    body = node.child_by_field_name("body")
    nested: list[TypeDecl] = []
    member_nodes = body.named_children if body is not None else []
    for member in member_nodes:
        if member.type == "field_declaration":
            declared = member.child_by_field_name("type")
            for declarator in member.children_by_field_name("declarator"):
                value = declarator.child_by_field_name("value")
                creates = None
                if value is not None and value.type == "object_creation_expression":
                    created = type_names(value.child_by_field_name("type"))
                    creates = created[0] if created else None
                fields.append(FieldDecl(text(declarator.child_by_field_name("name")), text(declared),
                                        type_names(declared), annotations_of(member), member.start_point[0] + 1, creates))
    field_types = {f.name: (f.type_names or [f.type])[0] for f in fields}
    methods = [_method(member, field_types) for member in member_nodes
               if member.type in ("method_declaration", "constructor_declaration", "compact_constructor_declaration")]
    methods += [c for c in components if c.name not in {m.name for m in methods}]
    for member in member_nodes:
        if member.type in TYPE_DECLARATIONS:
            nested += _type(member, package, qualified, path, source, imports)

    declared = TypeDecl(
        name=name, qualified=qualified, kind=TYPE_DECLARATIONS[node.type], package=package, file=path,
        line=node.start_point[0] + 1, end_line=node.end_point[0] + 1, annotations=annotations_of(node),
        extends=extends, implements=implements, fields=fields, methods=methods,
        source=source[node.start_byte:node.end_byte].decode("utf-8", "replace"), imports=imports,
    )
    return [declared] + nested


def parse_file(root: Path, file: Path, parser: Parser) -> FileParse:
    source = file.read_bytes()
    started = time.perf_counter()
    tree = parser.parse(source)
    parse_ms = (time.perf_counter() - started) * 1000
    path = file.relative_to(root).as_posix()
    package, imports, types = "", [], []
    categories = []
    for node in walk(tree.root_node):
        categories.append(category(node))
    for child in tree.root_node.named_children:
        if child.type == "package_declaration":
            package = next((text(c) for c in child.named_children if c.type in ("scoped_identifier", "identifier")), "")
        elif child.type == "import_declaration":
            imports.append(text(child).removeprefix("import").removesuffix(";").replace("static ", "").strip())
    for child in tree.root_node.named_children:
        if child.type in TYPE_DECLARATIONS:
            types += _type(child, package, None, path, source, imports)
    return FileParse(path, package, imports, types, len(categories), categories, parse_ms,
                     source.count(b"\n") + 1)


SKIP_DIRS = {".git", "target", "build", "node_modules", ".gradle", "out", ".idea", "generated", "generated-sources"}
TEST_DIRS = {"test", "tests", "androidTest", "testFixtures", "integrationTest", "it"}
MAX_FILE_BYTES = 1_000_000  # larger .java files are almost always generated
LANGUAGES = {".py": "Python", ".js": "JavaScript", ".ts": "TypeScript", ".tsx": "TypeScript", ".go": "Go",
             ".rs": "Rust", ".rb": "Ruby", ".php": "PHP", ".cs": "C#", ".kt": "Kotlin", ".scala": "Scala",
             ".swift": "Swift", ".c": "C", ".cpp": "C++", ".java": "Java"}


def java_files(root: Path, include_tests: bool = False) -> list[Path]:
    skip = SKIP_DIRS | (set() if include_tests else TEST_DIRS)
    return sorted(p for p in root.rglob("*.java")
                  if not (set(p.relative_to(root).parts[:-1]) & skip) and p.stat().st_size <= MAX_FILE_BYTES)


def languages(root: Path, limit: int = 20000) -> list[tuple[str, int]]:
    """The languages a repository is written in, by file count - for saying why nothing was parsed."""
    counts: dict[str, int] = {}
    for i, p in enumerate(root.rglob("*")):
        if i >= limit:
            break
        if p.suffix in LANGUAGES and not (set(p.relative_to(root).parts) & SKIP_DIRS):
            counts[LANGUAGES[p.suffix]] = counts.get(LANGUAGES[p.suffix], 0) + 1
    return sorted(counts.items(), key=lambda kv: -kv[1])


def new_parser() -> Parser:
    return Parser(JAVA)


class TypeIndex:
    """Resolves a simple type name, seen in one file, to an in-repository type - or to nothing."""

    def __init__(self, parses: list[FileParse]):
        self.types = {t.qualified: t for p in parses for t in p.types}
        self.by_simple: dict[str, list[str]] = {}
        for qualified, declared in self.types.items():
            self.by_simple.setdefault(declared.name, []).append(qualified)

    def resolve(self, simple: str, context: TypeDecl) -> str | None:
        """Java's own scoping order: member types of this class and its enclosing classes, then a
        single-type import, then the same package, then on-demand imports. A name imported from
        outside the repository, or visible from nowhere, resolves to nothing - never to a same-named
        class elsewhere in the repository."""
        if not simple:
            return None
        head, _, rest = simple.partition(".")  # Outer.Inner written in code
        enclosing = context.qualified
        while enclosing:
            if f"{enclosing}.{head}" in self.types:
                return self._member(f"{enclosing}.{head}", rest)
            if context.package and enclosing == context.package:
                break
            enclosing = enclosing.rpartition(".")[0]
        for imported in context.imports:
            if imported.endswith("." + head) and not imported.endswith(".*"):
                return self._member(imported, rest) if imported in self.types else None
        same_package = f"{context.package}.{head}" if context.package else head
        if same_package in self.types:
            return self._member(same_package, rest)
        for imported in context.imports:
            if imported.endswith(".*") and f"{imported[:-2]}.{head}" in self.types:
                return self._member(f"{imported[:-2]}.{head}", rest)
        return None

    def _member(self, qualified: str, rest: str) -> str | None:
        target = f"{qualified}.{rest}" if rest else qualified
        return target if target in self.types else None

    def declaring(self, qualified: str, method: str, depth: int = 10) -> str | None:
        """The in-repository type that declares `method`, starting at `qualified` and walking up its
        superclasses and interfaces. None when the declaration is outside the repository."""
        seen: set[str] = set()
        frontier = [qualified]
        while frontier and depth > 0:
            depth -= 1
            following = []
            for current in frontier:
                if current in seen or current not in self.types:
                    continue
                seen.add(current)
                declared = self.types[current]
                if any(m.name == method for m in declared.methods):
                    return current
                for parent in declared.extends + declared.implements:
                    resolved = self.resolve(parent, declared)
                    if resolved:
                        following.append(resolved)
            frontier = following
        return None


def _line(declared: TypeDecl, line: int) -> str:
    lines = declared.source.splitlines()
    index = line - declared.line
    return lines[index].strip() if 0 <= index < len(lines) else ""


def candidates(index: TypeIndex) -> list[Candidate]:
    """Every (A, B) where A's source names in-repository type B, with each place it does so."""
    found: dict[tuple[str, str], list[Evidence]] = {}

    def add(source: TypeDecl, simple: str, kind: str, how: str, line: int, method: str | None = None,
            resolved: str | None = None):
        target = resolved or index.resolve(simple, source)
        if target is None or target == source.qualified:
            return
        found.setdefault((source.id, "type:" + target), []).append(Evidence(kind, how, line, _line(source, line), method))

    for declared in index.types.values():
        for f in declared.fields:
            for i, simple in enumerate(f.type_names):
                if i == 0:
                    add(declared, simple, "field_type", f"field `{f.name}` has type {simple}", f.line)
                else:
                    add(declared, simple, "type_argument", f"field `{f.name}` holds {simple} inside {f.type}", f.line)
            if f.creates:
                add(declared, f.creates, "field_new", f"field `{f.name}` is initialised with new {f.creates}()", f.line)
        for m in declared.methods:
            for simple in m.param_types:
                add(declared, simple, "parameter_type", f"method `{m.name}` takes a {simple} parameter", m.line)
            for simple in sorted(set(m.type_names) - set(m.param_types)):
                add(declared, simple, "mentions_type", f"method `{m.name}` names type {simple} in its return type, generics or locals", m.line)
            for invocation in m.invocations:
                if invocation.receiver_type:
                    add(declared, invocation.receiver_type, "invocation",
                        f"method `{m.name}` calls `{invocation.receiver}.{invocation.method}(...)`", invocation.line,
                        invocation.method)
                    # An inherited method is called on the class that declares it.
                    receiver = index.resolve(invocation.receiver_type, declared)
                    owner = index.declaring(receiver, invocation.method) if receiver else None
                    if owner and owner != receiver:
                        add(declared, "", "invocation",
                            f"method `{m.name}` calls inherited `{invocation.receiver}.{invocation.method}(...)`",
                            invocation.line, invocation.method, resolved=owner)
                elif invocation.receiver is None and not any(x.name == invocation.method for x in declared.methods):
                    owner = index.declaring(declared.qualified, invocation.method)
                    if owner and owner != declared.qualified:
                        add(declared, "", "invocation", f"method `{m.name}` calls inherited `{invocation.method}(...)`",
                            invocation.line, invocation.method, resolved=owner)
            for created, line in m.creations:
                add(declared, created, "creation", f"method `{m.name}` constructs new {created}()", line)
    return [Candidate(s, t, evidence) for (s, t), evidence in sorted(found.items())]


def endpoints(declared: TypeDecl) -> list[dict]:
    """HTTP routes and message topics read straight from annotation values: syntax, not judgement."""
    base = ""
    for annotation in declared.annotations:
        if annotation.name == "RequestMapping":
            base = _first_string(annotation.args)
    out = []
    for m in declared.methods:
        for annotation in m.annotations:
            if annotation.name in MAPPING_ANNOTATIONS:
                path = "/" + "/".join(p.strip("/") for p in (base, _first_string(annotation.args)) if p.strip("/"))
                verb = MAPPING_ANNOTATIONS[annotation.name]
                out.append({"kind": "ENDPOINT", "id": f"endpoint:{verb}:{path}", "name": f"{verb} {path}",
                            "method": m.name, "line": m.line})
            elif annotation.name == "KafkaListener":
                for topic in re.findall(r'"([^"]+)"', annotation.args) or ["<dynamic-topic>"]:
                    out.append({"kind": "TOPIC", "id": f"topic:{topic}", "name": topic, "method": m.name, "line": m.line})
    return out


def _first_string(args: str) -> str:
    match = re.search(r'"([^"]*)"', args)
    return match.group(1) if match else ""


def syntax_kinds(candidate: Candidate, target: TypeDecl) -> dict[str, list[Evidence]]:
    """The relationship kinds syntax proves for one pair, each with the evidence that proves it."""
    declared = {m.name for m in target.methods}
    kinds: dict[str, list[Evidence]] = {}
    for e in candidate.evidence:
        if e.kind == "invocation":
            if e.method in declared:
                kinds.setdefault("CALLS", []).append(e)
        elif EDGE_FOR.get(e.kind):
            kinds.setdefault(EDGE_FOR[e.kind], []).append(e)
    return kinds


FRAMEWORKS = [  # import prefix -> what its presence tells a reader about the code
    ("org.springframework.web", "Spring Web: HTTP controllers and routes"),
    ("org.springframework.kafka", "Spring Kafka: message listeners and producers"),
    ("org.springframework.amqp", "Spring AMQP: RabbitMQ listeners and producers"),
    ("org.springframework.security", "Spring Security: access rules"),
    ("org.springframework.data", "Spring Data: repositories over stored data"),
    ("jakarta.persistence", "JPA: entities mapped to database tables"),
    ("javax.persistence", "JPA: entities mapped to database tables"),
    ("org.springframework.jdbc", "Spring JDBC: SQL access"),
    ("org.springframework.transaction", "Spring transactions"),
    ("org.springframework.stereotype", "Spring stereotypes: @Service, @Component, @Repository"),
    ("org.springframework.beans", "Spring beans and injected configuration values"),
    ("org.springframework.context", "Spring configuration and events"),
]


def frameworks(parses: list[FileParse]) -> list[str]:
    found = []
    imports = [i for p in parses for i in p.imports]
    for prefix, meaning in FRAMEWORKS:
        if any(i.startswith(prefix) for i in imports) and meaning not in found:
            found.append(meaning)
    return found

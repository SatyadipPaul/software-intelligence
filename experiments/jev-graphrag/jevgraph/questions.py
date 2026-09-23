"""The judgments Jev is asked for, the rule-based answers the offline stand-in gives instead, and a
check that refuses to send a badly formed question.

Design rules, from the primitive guidance:

- Ask only what syntax cannot prove. Declarations, DEPENDS_ON, CREATES and CALLS come from tree-sitter.
- A Choice always names a winner, so its options are mutually exclusive, map onto the product's own
  schema, and never include a catch-all. "None of these" is asked as its own Noul.
- When several labels can be true at once, ask one Noul per label.
- A Noul defines both outcomes as statements about the state. A Score's levels are ordered degrees,
  each a concrete situation. Instructions name the part of the state to inspect.
- Inferred values (an earlier answer) are labelled as inferred and never mixed with observed facts.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

from typesafe_sdk import Choice, Noul, Score

from .extract import Candidate, TypeDecl, endpoints

MAX_SOURCE_CHARS = 6000
# Caps that keep every request well inside Jev's context window on large repositories. Whatever is cut
# is counted in the state ("..._not_shown"), so the model knows the list is partial.
MAX_METHODS, MAX_FIELDS, MAX_NEIGHBOURS = 60, 40, 25
MAX_MEMBERS, MAX_RELATIONS, MAX_OUTSIDE = 25, 60, 40
GRAPH_BEING_BUILT = ("A knowledge graph of this codebase for answering questions about it: nodes are classes, "
                     "edges are relationships between classes, and groups of classes are business capabilities.")

# Keys match the product's EntityKind where one exists (CONTROLLER, SERVICE, REPOSITORY_COMPONENT, ENTITY,
# CONFIGURATION). The rest cover what the first real run showed web-app roles cannot: a CLI tool or a
# library's commands, core algorithms, data structures and serialisers. Every option says what it is
# and what it is not, so the options stay mutually exclusive.
ROLES = {
    "CONTROLLER": {"what": "Receives HTTP requests and hands them to other code.",
                   "signs": ["@RestController or @Controller", "methods mapped to HTTP routes, listed in `entry_points`"],
                   "not": "command-line commands or RPC/MCP servers: those are ENTRY_POINT"},
    "ENTRY_POINT": {"what": "Receives input from outside the application other than HTTP: a command-line command, an RPC or MCP server, a scheduled job or a main method.",
                    "signs": ["parses arguments or incoming requests", "a main method, a CLI framework annotation, or a request loop",
                              "hands the work to other classes"]},
    "MESSAGE_CONSUMER": {"what": "Reacts to messages or events that arrive from a queue, topic or event bus.",
                         "signs": ["@KafkaListener, @RabbitListener or @EventListener methods", "topics listed in `entry_points`"]},
    "SERVICE": {"what": "Coordinates one business use case of the application, usually called by a controller or consumer.",
                "signs": ["@Service", "calls repositories, clients or other services in a sequence"],
                "not": "a general algorithm or processing engine: that is ENGINE"},
    "ENGINE": {"what": "Implements a core algorithm or processing step - parsing, analysing, indexing, ranking, planning, building - over data it is given.",
               "signs": ["substantial logic of its own", "works with several collaborators or data structures"],
               "not": "a small stateless helper: that is UTILITY"},
    "REPOSITORY_COMPONENT": {"what": "Saves or loads stored records in a database on behalf of other code.",
                             "signs": ["@Repository or a Spring Data interface", "JPA, JDBC or SQL"],
                             "not": "writing files or JSON: that is SERIALIZER"},
    "ENTITY": {"what": "A business record that is mapped to a database table.",
               "signs": ["@Entity or @Table", "fields that become columns"],
               "not": "an in-memory data structure: that is DATA_MODEL"},
    "DATA_MODEL": {"what": "An in-memory data structure or value type: mostly fields, records or enums, little behaviour, not mapped to a database.",
                   "signs": ["a record, enum or class of fields", "passed between other classes"]},
    "EXTERNAL_CLIENT": {"what": "Calls a system outside this application: another service, a vendor API or a remote store.",
                        "signs": ["HTTP clients such as RestTemplate, WebClient or Feign", "remote URLs or a vendor SDK"],
                        "not": "only formatting data for a file or stream: that is SERIALIZER"},
    "SERIALIZER": {"what": "Converts objects to or from an external format: JSON, XML, CSV, files or a wire protocol.",
                   "signs": ["reads or writes files or streams", "names such as Json, Writer, Reader, Codec, Exporter"]},
    "CONFIGURATION": {"what": "Holds settings or sets the application up, rather than doing its work.",
                      "signs": ["@Configuration or @Bean methods", "an options or settings holder", "property binding"]},
    "UTILITY": {"what": "A small stateless helper with no collaborators: formatting, parsing a string, conversions.",
                "signs": ["mostly static methods", "no fields holding other classes"]},
}
ROLE_LIST = ("controller, entry point, message consumer, service, engine, repository component, entity, data model, "
             "external client, serializer, configuration or utility")
CATCH_ALL = {"OTHER", "NONE", "MIXED", "UNKNOWN", "N/A", "NA", "MISC", "ANY"}

COHESION = [
    "No entries in `relations` connect the members, and they share no entry point.",
    "The members share a package or vocabulary, but `relations` shows few links and none of them essential.",
    "The members form one call path in `relations`, but at least one member serves a different purpose.",
    "Every member takes part in the same flow or handles the same data, linked by essential relations.",
]

ROLE_SUFFIXES = {"controller", "service", "repository", "gateway", "consumer", "listener", "admin", "impl",
                 "handler", "manager", "event", "config", "configuration", "entity", "dto", "trail", "client"}
NAME_PROMISES = {"Controller": "CONTROLLER", "Service": "SERVICE", "Repository": "REPOSITORY_COMPONENT",
                 "Consumer": "MESSAGE_CONSUMER", "Listener": "MESSAGE_CONSUMER", "Config": "CONFIGURATION",
                 "Configuration": "CONFIGURATION", "Options": "CONFIGURATION", "Command": "ENTRY_POINT",
                 "Server": "ENTRY_POINT", "Json": "SERIALIZER", "Writer": "SERIALIZER", "Reader": "SERIALIZER",
                 "Codec": "SERIALIZER", "Gateway": "EXTERNAL_CLIENT", "Client": "EXTERNAL_CLIENT"}


@dataclass
class Ask:
    """One request: shared state plus independent questions, answered together in one call."""

    qid: str
    phase: str  # role | relation | community
    subject: str  # the graph id the answers attach to
    state: dict[str, Any]
    questions: dict[str, Any]
    stand_in: dict[str, dict]  # the offline rules' answers, in the same shape the API returns
    files: list[str] = field(default_factory=list)

    def wire_questions(self) -> dict[str, dict]:
        return {name: q.model_dump(mode="json", exclude_none=True) for name, q in self.questions.items()}


# ---------------------------------------------------------------- state

def context(repo: str, frameworks: list[str]) -> dict[str, Any]:
    return {"repository": repo, "language": "Java", "frameworks": frameworks or ["none detected from imports"],
            "graph_being_built": GRAPH_BEING_BUILT}


def type_card(declared: TypeDecl, with_source: bool) -> dict[str, Any]:
    card: dict[str, Any] = {
        "name": declared.name,
        "qualified_name": declared.qualified,
        "declaration": declared.kind,
        "package": declared.package,
        "annotations": [f"@{a.name}({a.args})" if a.args else f"@{a.name}" for a in declared.annotations],
        "extends": declared.extends,
        "implements": declared.implements,
        "fields": [{"name": f.name, "type": f.type, "annotations": [a.name for a in f.annotations]}
                   for f in declared.fields[:MAX_FIELDS]],
        "methods": [{"name": m.name, "parameters": [f"{t} {n}" for t, n in m.params], "returns": m.returns,
                     "annotations": [f"@{a.name}({a.args})" if a.args else f"@{a.name}" for a in m.annotations]}
                    for m in declared.methods[:MAX_METHODS]],
    }
    if len(declared.fields) > MAX_FIELDS:
        card["fields_not_shown"] = str(len(declared.fields) - MAX_FIELDS)
    if len(declared.methods) > MAX_METHODS:
        card["methods_not_shown"] = str(len(declared.methods) - MAX_METHODS)
    if with_source:
        card["code"] = declared.source[:MAX_SOURCE_CHARS]
    return card


def neighbours(declared: TypeDecl, pairs: list[Candidate], types: dict[str, TypeDecl]) -> tuple[list, list]:
    """Who this type uses and who uses it, from syntax: observed facts, not judgments. The most
    evidenced first; past the cap a final entry says how many were left out."""
    uses, used_by = [], []
    for c in sorted(pairs, key=lambda c: -len(c.evidence)):
        how = sorted({e.how for e in c.evidence})[:6]
        if c.source == declared.id:
            uses.append({"class": types[c.target].name, "how": how})
        elif c.target == declared.id:
            used_by.append({"class": types[c.source].name, "how": how})

    def cap(rows: list) -> list:
        return rows if len(rows) <= MAX_NEIGHBOURS else rows[:MAX_NEIGHBOURS] + [
            {"class": f"{len(rows) - MAX_NEIGHBOURS} more classes not shown", "how": []}]
    return cap(uses), cap(used_by)


# ---------------------------------------------------------------- stand-in rules (NOT Jev)

def _one_hot(labels, chosen: str) -> dict:
    return {"type": "choice", "choice": chosen, "confidence": 1.0,
            "probabilities": {label: 1.0 if label == chosen else 0.0 for label in labels}}


def _noul(value: bool | None) -> dict:
    """A rule that cannot tell answers 0.5 - undecided - rather than pretending."""
    return {"type": "noul", "noul": 0.5 if value is None else (1.0 if value else 0.0)}


def rule_role(declared: TypeDecl) -> str | None:
    """What annotations alone say - the evidence a framework-aware compiler pass uses. None if silent."""
    names = {a.name for a in declared.annotations}
    method_names = {a.name for m in declared.methods for a in m.annotations}
    if names & {"RestController", "Controller"}:
        return "CONTROLLER"
    if names & {"Repository"}:
        return "REPOSITORY_COMPONENT"
    if names & {"Entity", "Embeddable", "Document", "Table"}:
        return "ENTITY"
    if names & {"Configuration", "ConfigurationProperties"}:
        return "CONFIGURATION"
    if method_names & {"KafkaListener", "RabbitListener", "JmsListener", "EventListener", "StreamListener"}:
        return "MESSAGE_CONSUMER"
    if names & {"Service", "Component"}:
        return "SERVICE"
    return None


def name_promise(name: str) -> str | None:
    for suffix, role in NAME_PROMISES.items():
        if name.endswith(suffix):
            return role
    return None


# ---------------------------------------------------------------- round 1: role of each class

def role_ask(declared: TypeDecl, uses: list, used_by: list, ctx: dict, with_source: bool) -> Ask:
    state = {"context": ctx, "type": type_card(declared, with_source), "uses": uses, "used_by": used_by,
             "entry_points": [e["name"] for e in endpoints(declared)]}
    code_part = ", including its code in `type.code`" if with_source else ""
    questions = {
        "role": Choice(
            instructions={"question": "Which role does the class described in `type` play in this application?",
                          "inspect": ["type", "uses", "used_by", "entry_points"],
                          "focus": f"Judge by what the class declares and does{code_part}. Its name is a hint, not evidence."},
            criteria=ROLES),
        "fits_a_role": Noul(
            instructions={"question": f"Does the class in `type` plainly play one of these roles: {ROLE_LIST}?",
                          "inspect": ["type", "uses", "used_by"]},
            criteria={"true": "One of the listed roles describes the class's main job.",
                      "false": "None of them does: for example an exception type, test support or generated code."}),
        "name_misleads": Noul(
            instructions={"question": "Does the name in `type.name` promise a role or behaviour that the class does not deliver?",
                          "inspect": ["type"],
                          "focus": ("Compare what the name claims - Repository, Gateway, Controller, Service, Consumer, "
                                    "Command - with what the fields and methods actually do.")},
            criteria={"true": ("The name claims something the code does not do: for example a Repository that stores "
                               "nothing, or a Gateway that calls nothing outside the application."),
                      "false": "The class does what its name says, or its name makes no such claim."}),
    }
    rule = rule_role(declared)
    promise = name_promise(declared.name)
    stand_in = {
        "role": _one_hot(ROLES, rule or ("DATA_MODEL" if declared.kind in ("record", "enum") else "UTILITY")),
        "fits_a_role": _noul(True if rule else None),
        "name_misleads": _noul(None if promise is None or rule is None else promise != rule),
    }
    return Ask(qid=f"role:{declared.qualified}", phase="role", subject=declared.id, state=state,
               questions=questions, stand_in=stand_in, files=[declared.file])


# ---------------------------------------------------------------- round 2: what each link means

def relation_ask(candidate: Candidate, types: dict[str, TypeDecl], proven: dict[str, list], ctx: dict,
                 with_source: bool) -> Ask:
    source, target = types[candidate.source], types[candidate.target]
    evidence = [{"how": e.how, "line": str(e.line), **({"code": e.code} if with_source else {})} for e in candidate.evidence]
    state = {"context": ctx, "from_type": type_card(source, with_source), "to_type": type_card(target, with_source),
             "proven_by_syntax": sorted(proven), "evidence": evidence}
    inspect = ["from_type", "to_type", "evidence"]
    questions = {
        "essential": Noul(
            instructions={"question": "Does `from_type` delegate part of its own job to `to_type`?",
                          "inspect": inspect,
                          "focus": "What `from_type` exists to do, and whether it needs `to_type`'s operations to do it."},
            criteria={"true": ("`from_type` calls operations of `to_type` to carry out its own responsibility; removing "
                               "`to_type` would break that job."),
                      "false": ("`to_type` is incidental: only passed through, stored without being used, logged, or used "
                                "for its class name or other methods every object has.")}),
        "persists": Noul(
            instructions={"question": "Does `from_type` save `to_type` to a database, or load it from one?", "inspect": inspect},
            criteria={"true": ("`from_type` writes `to_type` records to, or reads them from, a database - tables, documents "
                               "or a key-value store - for example through JPA, JDBC, SQL or a Spring Data repository."),
                      "false": ("No database is involved. Writing `to_type` to a file, JSON, a stream or the console "
                                "does not count; that is serialising.")}),
        "serializes": Noul(
            instructions={"question": "Does `from_type` convert `to_type` to or from an external format?", "inspect": inspect},
            criteria={"true": "`from_type` turns `to_type` into, or builds it from, JSON, XML, CSV, a file or a wire message.",
                      "false": "`from_type` never converts `to_type` to or from any external format."}),
        "publishes": Noul(
            instructions={"question": "Does `from_type` send `to_type` as a message or event for other parts of the system?",
                          "inspect": inspect},
            criteria={"true": "`from_type` hands `to_type`, or data describing it, to a message broker, topic, queue or event bus.",
                      "false": "`from_type` sends no message or event carrying `to_type`."}),
    }
    stand_in = {"essential": _noul("CALLS" in proven), "persists": _noul(False), "serializes": _noul(None),
                "publishes": _noul(False)}
    return Ask(qid=f"relation:{source.qualified}->{target.qualified}", phase="relation",
               subject=f"{candidate.source}|{candidate.target}", state=state, questions=questions,
               stand_in=stand_in, files=sorted({source.file, target.file}))


# ---------------------------------------------------------------- round 3: what each community is

def _stems(name: str) -> list[str]:
    words = [w.lower() for w in re.findall(r"[A-Z][a-z0-9]*|[a-z0-9]+", name)]
    return [w for w in words if w not in ROLE_SUFFIXES and len(w) > 2]


def _singular(word: str) -> str:
    return word[:-1] if word.endswith("s") and len(word) > 4 else word


def label_candidates(members: list[TypeDecl]) -> dict[str, str]:
    """Names the code itself offers, each described by where it comes from. Ranked, at most 8."""
    origins: dict[str, dict[str, set]] = {}
    display: dict[str, str] = {}
    score: dict[str, int] = {}

    def note(word: str, origin: str, value: str, weight: int, prefer: bool = False):
        key = _singular(word.lower())
        if prefer or key not in display:
            display[key] = word.lower()
        origins.setdefault(key, {}).setdefault(origin, set()).add(value)
        score[key] = score.get(key, 0) + weight

    for declared in members:
        if declared.package:
            note(declared.package.split(".")[-1], "package", declared.package, 1)
        for entry in endpoints(declared):
            path = entry["name"].split(" ")[-1]
            root = path.strip("/").split("/")[0].split("-")[0]
            if root:
                note(root, "route" if entry["kind"] == "ENDPOINT" else "topic", path, 2, prefer=True)
        for stem in set(_stems(declared.name)):
            note(stem, "class names", declared.name, 1)
    # A word from the code that reads as a catch-all ("unknown", "misc") would be one; leave it out.
    ranked = sorted((k for k in score if display[k].upper() not in CATCH_ALL), key=lambda k: (-score[k], display[k]))[:8]
    return {display[k]: "Named by " + "; ".join(f"{origin} {', '.join(sorted(values))}"
                                                  for origin, values in sorted(origins[k].items()))
            for k in ranked}


def community_ask(cid: str, members: list[TypeDecl], inferred_roles: dict[str, dict], relations: list[dict],
                  outside: list[dict], ctx: dict, other_groups: list[str] | None = None) -> Ask:
    labels = label_candidates(members)
    hidden = max(0, len(members) - MAX_MEMBERS)
    members = members[:MAX_MEMBERS]  # the caller puts the members that matter most first
    member_cards = [{"class": m.name, "package": m.package,
                     "inferred_role": inferred_roles.get(m.id),  # an earlier answer, not an observed fact
                     "methods": [x.name for x in m.methods],
                     "entry_points": [e["name"] for e in endpoints(m)]} for m in members]
    state = {"context": ctx, "members": member_cards, "relations": relations[:MAX_RELATIONS],
             "links_outside": outside[:MAX_OUTSIDE], "other_groups": other_groups or []}
    for key, count in (("members_not_shown", hidden), ("relations_not_shown", len(relations) - MAX_RELATIONS),
                       ("links_outside_not_shown", len(outside) - MAX_OUTSIDE)):
        if count > 0:
            state[key] = str(count)
    questions: dict[str, Any] = {}
    stand_in: dict[str, dict] = {}
    if len(labels) >= 2:  # a choice between one option is not a question worth asking
        questions["label"] = Choice(
            instructions={"question": "Which name best describes what the group of classes in `members` does?",
                          "inspect": ["members", "relations", "other_groups"],
                          "focus": ("Each option says where in the code the name comes from. `other_groups` lists what the "
                                    "other groups are about; prefer a name that tells this group apart from them.")},
            criteria=labels)
        stand_in["label"] = _one_hot(labels, next(iter(labels)))
    questions["single_theme"] = Noul(
        instructions={"question": "Do the classes in `members` work toward one purpose that a single short name could describe?",
                      "inspect": ["members", "relations"]},
        criteria={"true": "The members serve one purpose, for example all take part in handling payments.",
                  "false": "The members serve unrelated purposes and were grouped only because some reference each other."})
    stand_in["single_theme"] = _noul(len({m.package for m in members}) == 1 or None)
    if len(members) > 1:  # cohesion of one class is meaningless
        questions["cohesion"] = Score(
            instructions={"question": "How tightly do the classes in `members` belong together as one unit?",
                          "inspect": ["members", "relations"]},
            criteria=COHESION)
        linked = sum(1 for r in relations if (r.get("essential") or 0) >= 0.5)
        level = 3 if linked >= len(members) - 1 else (2 if linked else 1)
        stand_in["cohesion"] = {"type": "score", "score": float(level), "confidence": 1.0,
                                "legend": {str(i): c for i, c in enumerate(COHESION)},
                                "probabilities": {str(i): 1.0 if i == level else 0.0 for i in range(len(COHESION))}}
    questions["business_capability"] = Noul(
        instructions={"question": ("Does the group in `members` provide a feature that a user of this application would name, "
                                   "given what the application is (see `context`)?"),
                      "inspect": ["members", "context"]},
        criteria={"true": ("Users would name it as something the application does for them: for a shop, taking payments; "
                           "for a developer tool, impact analysis or answering questions about code."),
                  "false": "It is internal plumbing that users never see: logging, configuration, shared helpers or data holders."})
    stand_in["business_capability"] = _noul(True if any(endpoints(m) for m in members) else None)
    return Ask(qid=f"community:{cid}", phase="community", subject=cid, state=state, questions=questions,
               stand_in=stand_in, files=sorted({m.file for m in members}))


# ---------------------------------------------------------------- the check every question must pass

_PATH = re.compile(r"`([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)`")


def _resolves(state: Any, parts: list[str]) -> bool:
    if not parts:
        return True
    if isinstance(state, dict):
        return parts[0] in state and _resolves(state[parts[0]], parts[1:])
    if isinstance(state, list):
        return any(_resolves(item, parts) for item in state)
    return False


def _texts(value: Any):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for v in value.values():
            yield from _texts(v)
    elif isinstance(value, list):
        for v in value:
            yield from _texts(v)


def lint(ask: Ask) -> list[str]:
    """Why this ask must not be sent - empty when it is well formed."""
    problems = []
    for name, question in ask.wire_questions().items():
        where = f"{ask.qid} / {name}"
        instructions = question.get("instructions")
        if not instructions:
            problems.append(f"{where}: no instructions")
        criteria = question.get("criteria") or {}
        if question["type"] == "noul":
            if not (criteria.get("true") and criteria.get("false")):
                problems.append(f"{where}: a Noul must define both the yes and the no outcome")
        elif question["type"] == "choice":
            if len(criteria) < 2:
                problems.append(f"{where}: a Choice needs at least two options")
            for label, description in criteria.items():
                if label.upper() in CATCH_ALL:
                    problems.append(f"{where}: catch-all option {label!r}; ask 'none of these' as its own Noul")
                if not description:
                    problems.append(f"{where}: option {label!r} has no description")
        elif question["type"] == "score":
            if len(criteria) < 3:
                problems.append(f"{where}: a Score needs at least three ordered levels")
        for text in list(_texts(instructions)) + list(_texts(criteria)):
            for path in _PATH.findall(text):
                if not _resolves(ask.state, path.split(".")):
                    problems.append(f"{where}: `{path}` is not in the state")
        if isinstance(instructions, dict):
            for part in instructions.get("inspect", []):
                if not _resolves(ask.state, part.split(".")):
                    problems.append(f"{where}: inspect target {part!r} is not in the state")
    return problems

"""The judgments Jev is asked for, and the rule-based answers the offline stand-in gives instead.

Only questions syntax cannot settle are here: which architectural role a type plays, what kind of
relationship a mention is, whether one type really invokes another's operations, and what a detected
community is about. Declarations, imports, inheritance and annotation values never reach a model.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

from typesafe_sdk import Choice, Noul, Score

from .extract import Candidate, TypeDecl, endpoints

MAX_SOURCE_CHARS = 6000

ROLES = {
    "CONTROLLER": "Receives requests from outside the application - HTTP routes, RPC calls, UI actions - and hands them to other code.",
    "SERVICE": "Holds business logic or coordinates one use case across other components.",
    "REPOSITORY": "Reads or writes stored data - a database, a table, a store - on behalf of other code.",
    "ENTITY": "Is mainly a data record: fields describing one business object, with little behaviour.",
    "CONSUMER": "Reacts to messages or events that arrive from a queue, topic or event bus.",
    "GATEWAY": "Wraps a call to a system outside this application, such as a payment provider or a remote API.",
    "CONFIGURATION": "Sets the application up: wiring, bean definitions, properties or settings.",
    "OTHER": "None of the above fits, for example a utility, a helper or a constant holder.",
}

RELATIONS = {
    "DEPENDS_ON": "The source keeps or receives the target as a collaborator and uses it to do its own work.",
    "CALLS": "The source calls the target's operations without keeping it, for example a static or one-off call.",
    "CREATES": "The source only constructs target instances and hands them on, like a factory, without using them.",
    "PERSISTS": "The source saves or loads the target as stored data.",
    "PUBLISHES": "The source sends the target as a message or event for others to receive.",
    "USES_TYPE": "The source only mentions the target's type, in a parameter, return value or local, without using its behaviour.",
    "NONE": "The mention is incidental and there is no real relationship.",
}

COHESION = [
    "The members are unrelated pieces that ended up together by accident.",
    "The members are loosely related: they share some vocabulary but serve different purposes.",
    "The members mostly serve one business area, with one or two outsiders.",
    "Every member works with the others on one recognisable business capability.",
]

ROLE_SUFFIXES = {"controller", "service", "repository", "gateway", "consumer", "listener", "admin", "impl",
                 "handler", "manager", "event", "config", "configuration", "entity", "dto", "trail"}


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


def type_card(declared: TypeDecl, with_source: bool) -> dict[str, Any]:
    card: dict[str, Any] = {
        "name": declared.name,
        "qualified_name": declared.qualified,
        "declaration": declared.kind,
        "package": declared.package,
        "annotations": [f"@{a.name}({a.args})" if a.args else f"@{a.name}" for a in declared.annotations],
        "extends": declared.extends,
        "implements": declared.implements,
        "fields": [{"name": f.name, "type": f.type, "annotations": [a.name for a in f.annotations]} for f in declared.fields],
        "methods": [{"name": m.name, "parameters": [f"{t} {n}" for t, n in m.params], "returns": m.returns,
                     "annotations": [f"@{a.name}({a.args})" if a.args else f"@{a.name}" for a in m.annotations]}
                    for m in declared.methods],
    }
    if with_source:
        card["source"] = declared.source[:MAX_SOURCE_CHARS]
    return card


def _one_hot(labels, chosen: str) -> dict:
    return {"type": "choice", "choice": chosen, "confidence": 1.0,
            "probabilities": {label: 1.0 if label == chosen else 0.0 for label in labels}}


def _noul(value: bool) -> dict:
    return {"type": "noul", "noul": 1.0 if value else 0.0}


def rule_role(declared: TypeDecl) -> str:
    """What annotations alone say - the same evidence a framework-aware compiler pass uses."""
    names = {a.name for a in declared.annotations}
    method_names = {a.name for m in declared.methods for a in m.annotations}
    if names & {"RestController", "Controller"}:
        return "CONTROLLER"
    if names & {"Repository"}:
        return "REPOSITORY"
    if names & {"Entity", "Embeddable", "Document", "Table"}:
        return "ENTITY"
    if names & {"Configuration", "ConfigurationProperties"}:
        return "CONFIGURATION"
    if method_names & {"KafkaListener", "RabbitListener", "JmsListener", "EventListener", "StreamListener"}:
        return "CONSUMER"
    if names & {"Service", "Component"}:
        return "SERVICE"
    return "OTHER"


def role_ask(declared: TypeDecl, with_source: bool) -> Ask:
    question = Choice(
        instructions=("Which architectural role does the type described in `type` play in this application? "
                      "Judge from what it declares and does - annotations, fields, method names and, when "
                      "present, `type.source` - not from its name alone."),
        criteria=ROLES,
    )
    return Ask(
        qid=f"role:{declared.qualified}", phase="role", subject=declared.id,
        state={"type": type_card(declared, with_source), "file": declared.file},
        questions={"role": question},
        stand_in={"role": _one_hot(ROLES, rule_role(declared))},
        files=[declared.file],
    )


def relation_ask(candidate: Candidate, types: dict[str, TypeDecl], with_source: bool) -> Ask:
    source, target = types[candidate.source], types[candidate.target]
    target_methods = {m.name for m in target.methods}
    # The code line is source text, so it goes only when source may be sent.
    evidence = [{"how": e.how, "line": e.line, **({"code": e.code} if with_source else {})} for e in candidate.evidence]
    questions = {
        "relation": Choice(
            instructions=("`source` mentions `target` at the places listed in `evidence`. Which option best "
                          "describes the relationship from `source` to `target`?"),
            criteria=RELATIONS,
        ),
        "invokes": Noul(
            instructions=("Does code in `source` invoke at least one operation that `target` itself declares, "
                          "as listed in `target.methods`?"),
            criteria={"true": "At least one call in `evidence` or `source.source` runs a method declared in `target.methods`.",
                      "false": ("No call reaches a method `target` declares; calls to methods every object inherits, "
                                "such as getClass, toString or hashCode, do not count.")},
        ),
    }
    invokes = any(e.method in target_methods for e in candidate.evidence if e.method)
    holds = any(e.how.startswith("field") for e in candidate.evidence)
    uses = holds or invokes
    rule_relation = "DEPENDS_ON" if uses else ("CREATES" if any("new " in e.how for e in candidate.evidence) else "USES_TYPE")
    return Ask(
        qid=f"relation:{source.qualified}->{target.qualified}", phase="relation",
        subject=f"{candidate.source}|{candidate.target}",
        state={"source": type_card(source, with_source),
               "target": {k: v for k, v in type_card(target, False).items() if k != "fields"},
               "evidence": evidence},
        questions=questions,
        stand_in={"relation": _one_hot(RELATIONS, rule_relation), "invokes": _noul(invokes)},
        files=sorted({source.file, target.file}),
    )


def _stems(name: str) -> list[str]:
    words = [w.lower() for w in re.findall(r"[A-Z][a-z0-9]*|[a-z0-9]+", name)]
    return [w for w in words if w not in ROLE_SUFFIXES and len(w) > 2]


def label_candidates(members: list[TypeDecl]) -> list[str]:
    """Names the code itself offers: package segments, route and topic roots, and name stems."""
    counts: dict[str, int] = {}
    for declared in members:
        seen = set()
        if declared.package:
            seen.add(declared.package.split(".")[-1].lower())
        for entry in endpoints(declared):
            root = entry["name"].split(" ")[-1].strip("/").split("/")[0].split("-")[0]
            if root:
                seen.add(root.lower())
        seen.update(_stems(declared.name))
        for word in seen:
            counts[word] = counts.get(word, 0) + 1
    ranked = sorted(counts, key=lambda w: (-counts[w], w))
    return ranked[:8]


def community_ask(cid: str, members: list[TypeDecl], roles: dict[str, str], relations: list[dict]) -> Ask:
    labels = label_candidates(members)
    criteria = {label: f"The group is mainly about {label}." for label in labels}
    criteria["mixed"] = "The group has no single theme; none of the other names fits."
    member_cards = [{"name": m.name, "package": m.package, "role": roles.get(m.id, "unknown"),
                     "methods": [x.name for x in m.methods],
                     "entry_points": [e["name"] for e in endpoints(m)]} for m in members]
    questions = {
        "label": Choice(instructions=("`members` were grouped together because they depend on one another. Which "
                                      "name best describes what this group is about?"), criteria=criteria),
        "cohesion": Score(instructions="How tightly do `members` belong together as one unit of the application?",
                          criteria=COHESION),
        "business_capability": Noul(
            instructions=("Does this group implement a business capability a product manager would recognise, "
                          "such as taking payments, rather than technical plumbing?")),
    }
    rule_label = labels[0] if labels else "mixed"
    packages = {m.package for m in members}
    rule_cohesion = 3 if len(packages) == 1 and len(members) > 1 else (2 if len(members) > 1 else 1)
    return Ask(
        qid=f"community:{cid}", phase="community", subject=cid,
        state={"members": member_cards, "relations": relations},
        questions=questions,
        stand_in={"label": _one_hot(criteria, rule_label),
                  "cohesion": {"type": "score", "score": float(rule_cohesion), "confidence": 1.0,
                               "legend": {str(i): c for i, c in enumerate(COHESION)},
                               "probabilities": {str(i): 1.0 if i == rule_cohesion else 0.0 for i in range(len(COHESION))}},
                  "business_capability": _noul(any(e for m in members for e in endpoints(m)))},
        files=sorted({m.file for m in members}),
    )

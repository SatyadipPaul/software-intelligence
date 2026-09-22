"""Grade the graph against the repository's own Java analyzer (JDT) graph.

Two different things are graded, and the report keeps them apart:

- the parser: syntax edges (DEPENDS_ON, CALLS) against compiler bindings - no model involved;
- the judge: roles where the compiler sees a framework role, and PERSISTS / PUBLISHES answers.

Where the compiler has no answer - a class with no annotation has no role it can see, and nothing
in a compiler says whether a link is essential - the judge's answers are listed for a person to
read, not scored.
"""

from __future__ import annotations

TRUTH_ROLES = {"CONTROLLER": "CONTROLLER", "SERVICE": "SERVICE", "REPOSITORY_COMPONENT": "REPOSITORY_COMPONENT",
               "ENTITY": "ENTITY", "CONFIGURATION": "CONFIGURATION"}


def _owner(member_id: str) -> str:
    return member_id.split("#")[0].split(".field:")[0]


def _prf(predicted: set, truth: set) -> dict:
    hit = len(predicted & truth)
    return {"predicted": len(predicted), "truth": len(truth), "correct": hit,
            "precision": hit / len(predicted) if predicted else None, "recall": hit / len(truth) if truth else None,
            "false_positives": sorted(predicted - truth), "missed": sorted(truth - predicted)}


def _pct(value) -> str:
    return "n/a" if value is None else f"{value:.0%}"


def _short(pair) -> str:
    return " → ".join(p.split(".")[-1] for p in pair)


def _listing(pairs, limit: int = 15) -> str:
    shown = ", ".join(_short(p) for p in pairs[:limit])
    return shown + (f" and {len(pairs) - limit} more" if len(pairs) > limit else "")


def _p(value) -> str:
    return "-" if value is None else f"{value:.2f}"


def grade(graphrag: dict, truth: dict, candidates) -> dict:
    in_repo = {n["id"] for n in truth["nodes"] if n["id"].startswith("type:") and "#" not in n["id"]
               and ".field:" not in n["id"] and n["kind"] != "EXTERNAL_SYMBOL"}
    truth_kind = {n["id"]: n["kind"] for n in truth["nodes"] if n["id"] in in_repo}
    consumers = {_owner(e["from"]) for e in truth["edges"] if e["kind"] == "CONSUMES"}

    def constructor(member_id: str) -> bool:  # type:a.B#B(...) - the analyzer counts `new B()` as a call
        owner, _, member = member_id.partition("#")
        return member.split("(")[0] in (owner.rsplit(".", 1)[-1], "<init>")

    def truth_pairs(kind: str) -> set:
        # CALLS here means calling a method the target declares; constructor calls are CREATES.
        pairs = {(_owner(e["from"]), _owner(e["to"])) for e in truth["edges"]
                 if e["kind"] == kind and not (kind == "CALLS" and constructor(e["to"]))}
        return {p for p in pairs if p[0] in in_repo and p[1] in in_repo and p[0] != p[1]}

    rels = graphrag["relationships"]
    syntax = lambda kind: {(r["source"], r["target"]) for r in rels if r["type"] == kind and r["origin"] == "syntax"}
    judged = lambda kind: {(r["source"], r["target"]) for r in rels if r["type"] == kind and r["origin"] != "syntax"}

    # The parser
    depends = _prf(syntax("DEPENDS_ON"), truth_pairs("DEPENDS_ON"))
    calls = _prf(syntax("CALLS"), truth_pairs("CALLS"))
    candidate_pairs = {(c.source, c.target) for c in candidates}
    truth_depends = truth_pairs("DEPENDS_ON")
    coverage = len(truth_depends & candidate_pairs) / len(truth_depends) if truth_depends else None
    ours = {e["id"] for e in graphrag["entities"] if e["type"] in ("ENDPOINT", "TOPIC")}
    theirs = {n["id"] for n in truth["nodes"] if n["kind"] in ("ENDPOINT", "TOPIC")}

    # The judge: roles
    rows, scored, correct = [], 0, 0
    types = {e["id"]: e for e in graphrag["entities"] if e["type"] == "TYPE"}
    for entity in types.values():
        if entity.get("role") is None and not entity.get("focus", True):
            continue  # not sent to the judge: nothing to grade or to show
        expected = TRUTH_ROLES.get(truth_kind.get(entity["id"], ""))
        if expected is None and entity["id"] in consumers:
            expected = "MESSAGE_CONSUMER"
        predicted = entity.get("role")
        if expected is not None and predicted is not None:
            scored += 1
            correct += int(expected == predicted)
        rows.append({"type": entity["title"], "compiler": expected, "judge": predicted,
                     "confidence": entity.get("role_confidence"), "fits": entity.get("fits_a_role"),
                     "misleads": entity.get("name_misleads")})
    roles = {"scored": scored, "correct": correct, "accuracy": correct / scored if scored else None}

    # The judge: kinds of link only a reading of the code can find. The fixture has none, so this counts false alarms.
    persists = _prf(judged("PERSISTS"), truth_pairs("PERSISTS"))
    publishes = _prf(judged("PUBLISHES"), truth_pairs("PUBLISHES"))
    weighed = sorted({(r["source"], r["target"]): r.get("essential") for r in rels
                      if r["origin"] == "syntax" and r.get("essential") is not None}.items())

    known = {n["name"].split(".")[-1].lower() for n in truth["nodes"] if n["kind"] in ("BUSINESS_CAPABILITY", "MODULE")}

    judge = graphrag["judge"]
    answered = any(r["judge"] for r in rows) or bool(weighed)
    lines = [f"# Grade: `{graphrag['repo']}`, judged by `{judge}`", "",
             "Reference: the repository's own Java analyzer (Eclipse JDT, compiler bindings).", ""]
    if judge == "standin-rules":
        lines += ["> **The judge here is the offline rule stand-in, not Jev.** It proves the pipeline and sets the "
                  "baseline Jev has to match; it says nothing about Jev. A rule that cannot tell answers 0.50.", ""]
    if not answered:
        lines += ["> Dry run: no answers, so only the parser is graded.", ""]
    lines += ["## The parser (tree-sitter, no model)", "",
              f"Real dependencies put in front of the judge: **{_pct(coverage)}** ({len(truth_depends & candidate_pairs)} of {len(truth_depends)}).", "",
              "| Syntax edge | Precision | Recall | Correct / found / compiler |", "|---|---|---|---|",
              f"| DEPENDS_ON | {_pct(depends['precision'])} | {_pct(depends['recall'])} | {depends['correct']} / {depends['predicted']} / {depends['truth']} |",
              f"| CALLS | {_pct(calls['precision'])} | {_pct(calls['recall'])} | {calls['correct']} / {calls['predicted']} / {calls['truth']} |", ""]
    for name, result in (("DEPENDS_ON", depends), ("CALLS", calls)):
        if result["false_positives"]:
            lines.append(f"- {name} found only by the parser: " + _listing(result["false_positives"]))
        if result["missed"]:
            lines.append(f"- {name} missed by the parser: " + _listing(result["missed"]))
    budget = graphrag.get("budget")
    if budget:
        lines += [f"- Sent to the judge: {budget['classes_judged']} of {budget['classes']} classes (most connected first) "
                  f"and {budget['links_judged']} of {budget['links']} links; everything else is graded here as syntax only."]
    lines += [f"- Entry points found only by tree-sitter: {sorted(ours - theirs) or 'none'}; "
              f"only by the analyzer: {sorted(theirs - ours) or 'none'}", "",
              "## The judge: roles", "",
              f"Scored where the compiler sees a framework role: **{_pct(roles['accuracy'])}** ({correct} of {scored}).", "",
              "| Class | Compiler says | Judge says | Confidence | Fits a role | Name misleads |", "|---|---|---|---|---|---|"]
    for row in rows:
        lines.append(f"| {row['type']} | {row['compiler'] or '(none visible)'} | {row['judge'] or '-'} | "
                     f"{_p(row['confidence'])} | {_p(row['fits'])} | {_p(row['misleads'])} |")
    lines += ["", "*(none visible)*: no annotation, so the compiler has no role to compare with; the judge's reading "
              "is for a person to accept or reject. *Fits a role* and *name misleads* are probabilities of yes.", "",
              "## The judge: links", "",
              f"- PERSISTS: {persists['predicted']} claimed, {persists['correct']} confirmed by the compiler"
              + (f"; false alarms: {', '.join(_short(p) for p in persists['false_positives'])}" if persists["false_positives"] else ""),
              f"- PUBLISHES: {publishes['predicted']} claimed, {publishes['correct']} confirmed by the compiler"
              + (f"; false alarms: {', '.join(_short(p) for p in publishes['false_positives'])}" if publishes["false_positives"] else ""),
              "", "Essential (probability that the first class delegates part of its job to the second; used as the edge weight):", "",
              "| Link | Essential |", "|---|---|"]
    lines += [f"| {_short(pair)} | {_p(value)} |" for pair, value in weighed] or ["| - | - |"]
    lines += ["", "## Communities", "", "| Members | Label | Names an analyzer module/capability | One theme | Cohesion | Business capability |",
              "|---|---|---|---|---|---|"]
    communities = []
    for c in graphrag["communities"]:
        if not c.get("judged", True):
            continue
        members = [m.split(".")[-1] for m in c["members"]]
        match = (c.get("label") or "") in known
        communities.append({"members": members, "label": c.get("label"), "matches_analyzer_name": match})
        cohesion = "n/a (one class)" if c["size"] == 1 else (f"{c['cohesion']:.2f} / 3" if c.get("cohesion") is not None else "-")
        lines.append(f"| {', '.join(members)} | {c.get('label') or '-'} | {'yes' if match else 'no'} | "
                     f"{_p(c.get('single_theme'))} | {cohesion} | {_p(c.get('business_capability'))} |")
    return {"candidate_coverage": coverage, "roles": roles,
            "syntax_depends_on": {k: depends[k] for k in ("precision", "recall", "correct", "predicted", "truth")},
            "syntax_calls": {k: calls[k] for k in ("precision", "recall", "correct", "predicted", "truth")},
            "persists_claimed": persists["predicted"], "publishes_claimed": publishes["predicted"],
            "persists_false_positives": len(persists["false_positives"]),
            "publishes_false_positives": len(publishes["false_positives"]),
            "communities": communities,
            "entry_points": {"ours_only": sorted(ours - theirs), "analyzer_only": sorted(theirs - ours)},
            "markdown": "\n".join(lines) + "\n"}

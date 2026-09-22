"""Grade the judged graph against the repository's own Java analyzer (JDT) graph.

The JDT graph is compiler-backed, so where it has an answer it is the reference. Where it has none -
a class with no framework annotation has no role the compiler can see - the judge's answer is listed
for a person to read, not scored.
"""

from __future__ import annotations

TRUTH_ROLES = {"CONTROLLER": "CONTROLLER", "SERVICE": "SERVICE", "REPOSITORY_COMPONENT": "REPOSITORY",
               "ENTITY": "ENTITY", "CONFIGURATION": "CONFIGURATION"}


def _owner(member_id: str) -> str:
    return member_id.split("#")[0].split(".field:")[0]


def _prf(predicted: set, truth: set) -> dict:
    hit = len(predicted & truth)
    precision = hit / len(predicted) if predicted else None
    recall = hit / len(truth) if truth else None
    return {"predicted": len(predicted), "truth": len(truth), "correct": hit, "precision": precision, "recall": recall,
            "false_positives": sorted(predicted - truth), "missed": sorted(truth - predicted)}


def _pct(value) -> str:
    return "n/a" if value is None else f"{value:.0%}"


def _short(pair) -> str:
    return " → ".join(p.split(".")[-1] for p in pair)


def grade(graphrag: dict, truth: dict, candidates) -> dict:
    in_repo = {n["id"] for n in truth["nodes"] if n["id"].startswith("type:") and "#" not in n["id"]
               and ".field:" not in n["id"] and n["kind"] != "EXTERNAL_SYMBOL"}
    truth_kind = {n["id"]: n["kind"] for n in truth["nodes"] if n["id"] in in_repo}
    consumers = {_owner(e["from"]) for e in truth["edges"] if e["kind"] == "CONSUMES"}

    # Roles
    rows, scored, correct = [], 0, 0
    for entity in graphrag["entities"]:
        if entity["type"] != "TYPE":
            continue
        expected = TRUTH_ROLES.get(truth_kind.get(entity["id"], ""))
        if expected is None and entity["id"] in consumers:
            expected = "CONSUMER"
        predicted = entity.get("role")
        if expected is not None and predicted is not None:
            scored += 1
            correct += int(expected == predicted)
        rows.append({"type": entity["title"], "compiler": expected or "(none visible)", "judge": predicted,
                     "confidence": entity.get("role_confidence")})
    roles = {"scored": scored, "correct": correct, "accuracy": correct / scored if scored else None, "rows": rows}

    # Relationships between in-repository types
    truth_depends = {(e["from"], e["to"]) for e in truth["edges"]
                     if e["kind"] == "DEPENDS_ON" and e["from"] in in_repo and e["to"] in in_repo}
    truth_calls = {(_owner(e["from"]), _owner(e["to"])) for e in truth["edges"] if e["kind"] == "CALLS"}
    truth_calls = {p for p in truth_calls if p[0] in in_repo and p[1] in in_repo and p[0] != p[1]}
    judged = [r for r in graphrag["relationships"] if r.get("origin") != "syntax"]
    predicted_depends = {(r["source"], r["target"]) for r in judged if r["type"] == "DEPENDS_ON"}
    predicted_calls = {(r["source"], r["target"]) for r in judged if (r.get("invokes_probability") or 0) >= 0.5}
    candidate_pairs = {(c.source, c.target) for c in candidates}
    depends = _prf(predicted_depends, truth_depends)
    calls = _prf(predicted_calls, truth_calls)
    coverage = len(truth_depends & candidate_pairs) / len(truth_depends) if truth_depends else None

    # Entry points
    ours = {e["id"] for e in graphrag["entities"] if e["type"] in ("ENDPOINT", "TOPIC")}
    theirs = {n["id"] for n in truth["nodes"] if n["kind"] in ("ENDPOINT", "TOPIC")}

    # Communities, informational: do the labels name a capability or module the analyzer found?
    known = {n["name"].split(".")[-1].lower() for n in truth["nodes"] if n["kind"] in ("BUSINESS_CAPABILITY", "MODULE")}
    communities = [{"members": [m.split(".")[-1] for m in c["members"]], "label": c.get("label"),
                    "matches_analyzer_name": (c.get("label") or "") in known, "cohesion": c.get("cohesion")}
                   for c in graphrag["communities"]]

    judged_any = any(r.get("judge") for r in rows) or bool(judged)
    lines = [f"# Grade: `{graphrag['repo']}`, judged by `{graphrag['judge']}`", "",
             "Reference: the repository's own Java analyzer (Eclipse JDT, compiler bindings).", ""]
    if graphrag["judge"] == "standin-rules":
        lines += ["> **These answers come from the offline rule stand-in, not Jev.** They show the pipeline works "
                  "and set the baseline Jev has to match; they say nothing about Jev.", ""]
    if not judged_any:
        lines += ["> Dry run: no answers, so only the tree-sitter parts below are graded.", ""]
    lines += ["## Candidate pairs from tree-sitter", "",
              f"Real dependencies the parser put in front of the judge: **{_pct(coverage)}** "
              f"({len(truth_depends & candidate_pairs)} of {len(truth_depends)}). The judge cannot find a pair it is never shown.", "",
              "## Roles", "", f"Scored where the compiler sees a framework role: **{_pct(roles['accuracy'])}** "
              f"({correct} of {scored}).", "", "| Type | Compiler says | Judge says | Confidence |", "|---|---|---|---|"]
    for row in rows:
        confidence = "" if row["confidence"] is None else f"{row['confidence']:.2f}"
        lines.append(f"| {row['type']} | {row['compiler']} | {row['judge'] or '-'} | {confidence} |")
    lines += ["", "Rows marked *(none visible)* have no annotation, so the compiler has no role to compare with. "
              "Those answers are the judge's own reading, for a person to accept or reject.", "",
              "## Relationships", "", "| Question | Precision | Recall | Correct / predicted / truth |", "|---|---|---|---|",
              f"| relation = DEPENDS_ON | {_pct(depends['precision'])} | {_pct(depends['recall'])} | "
              f"{depends['correct']} / {depends['predicted']} / {depends['truth']} |",
              f"| invokes a declared operation | {_pct(calls['precision'])} | {_pct(calls['recall'])} | "
              f"{calls['correct']} / {calls['predicted']} / {calls['truth']} |", ""]
    for name, result in (("DEPENDS_ON", depends), ("invokes", calls)):
        if result["false_positives"]:
            lines.append(f"- {name} false positives: " + ", ".join(_short(p) for p in result["false_positives"]))
        if result["missed"]:
            lines.append(f"- {name} missed: " + ", ".join(_short(p) for p in result["missed"]))
    lines += ["", "## Entry points (syntax, no judge)", "",
              f"- Found by tree-sitter only: {sorted(ours - theirs) or 'none'}",
              f"- Found by the analyzer only: {sorted(theirs - ours) or 'none'}",
              f"- Both: {sorted(ours & theirs) or 'none'}", "",
              "## Communities (informational)", "", "| Members | Label | Names an analyzer module/capability | Cohesion |",
              "|---|---|---|---|"]
    for c in communities:
        cohesion = "" if c["cohesion"] is None else f"{c['cohesion']:.2f} / 3"
        lines.append(f"| {', '.join(c['members'])} | {c['label'] or '-'} | {'yes' if c['matches_analyzer_name'] else 'no'} | {cohesion} |")
    return {"candidate_coverage": coverage, "roles": {k: v for k, v in roles.items() if k != "rows"},
            "depends_on": {k: depends[k] for k in ("precision", "recall", "correct", "predicted", "truth")},
            "invokes": {k: calls[k] for k in ("precision", "recall", "correct", "predicted", "truth")},
            "entry_points": {"ours_only": sorted(ours - theirs), "analyzer_only": sorted(theirs - ours)},
            "markdown": "\n".join(lines) + "\n"}

"""Parse -> ask -> assemble, as a stream of events, saving every piece as soon as it exists.

The same generator drives the CLI and the live UI, so what the page shows is what lands on disk.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

import networkx as nx

from . import extract, fetch
from .extract import TypeIndex
from .grade import grade
from .judges import AnswerCache, DryRunJudge, JevJudge, StandInJudge, load_env
from .questions import Ask, community_ask, context, label_candidates, lint, neighbours, relation_ask, role_ask

HERE = Path(__file__).resolve().parent.parent
REPO_ROOT = HERE.parent.parent
OUT = HERE / "out"
PRICE_PER_MILLION_INPUT = 0.042  # vendor-stated, USD; output tokens stated as free
RUN_FILES = ["events", "ast", "entities", "relationships", "text_units", "questions", "answers",
             "communities", "requests"]


class Writer:
    """Appends one JSON line per record and flushes it, so a stopped run leaves everything so far."""

    def __init__(self, directory: Path):
        self.directory = directory
        directory.mkdir(parents=True, exist_ok=True)
        for name in RUN_FILES:
            (directory / f"{name}.jsonl").unlink(missing_ok=True)
        self.handles = {}

    def append(self, name: str, record: dict) -> None:
        handle = self.handles.get(name)
        if handle is None:
            handle = self.handles[name] = (self.directory / f"{name}.jsonl").open("a", encoding="utf-8")
        handle.write(json.dumps(record) + "\n")
        handle.flush()

    def write(self, name: str, content: str) -> None:
        (self.directory / name).write_text(content, encoding="utf-8")  # Windows would default to cp1252

    def close(self) -> None:
        for handle in self.handles.values():
            handle.close()


def make_judge(mode: str, out_dir: Path, out_root: Path = OUT):
    env = load_env()
    model = env.get("TYPESAFE_DEFAULT_MODEL", "jev-latest")
    if mode == "standin":
        return StandInJudge(), None
    if mode == "dryrun":
        return DryRunJudge(out_dir / "requests.jsonl", model), None
    if mode == "jev":
        key = env.get("TYPESAFE_API_KEY")
        cache = AnswerCache(out_root / "jev_cache.jsonl")
        if not key and not cache.entries:
            return None, ("No TYPESAFE_API_KEY found. Put it in experiments/jev-graphrag/.env "
                          "(see .env.example) or export it, then run again - or pick the stand-in or dry-run mode.")
        return JevJudge(cache, key or "cache-only-no-key", model, env.get("TYPESAFE_BASE_URL")), None
    return None, f"Unknown mode {mode!r}; use jev, standin or dryrun."


@dataclass
class Budget:
    """How much of a repository is sent to the judge. Everything is parsed and saved regardless;
    only the questions are budgeted, most-connected first."""

    roles: int = 40
    links: int = 60
    communities: int = 12
    include_tests: bool = False
    max_files: int = 5000

    @classmethod
    def unlimited(cls) -> "Budget":
        return cls(10**9, 10**9, 10**9, False, 10**9)


BUDGETS = {"small": Budget(15, 20, 5), "normal": Budget(), "large": Budget(120, 200, 30)}


def _auto_truth(repo: Path, target: Path, include_tests: bool) -> subprocess.Popen | None:
    """Start this repository's own Java analyzer in the background, if it has been built, to write a
    reference graph to grade against. It runs while the judge works."""
    jar = REPO_ROOT / "apps" / "cli" / "target" / "repo-intel.jar"
    if not jar.exists() or shutil.which("java") is None:
        return None
    target.parent.mkdir(parents=True, exist_ok=True)
    target.unlink(missing_ok=True)  # never grade against a previous run's graph
    # Same scope as the parser: when tests are skipped here, the reference skips them too.
    return subprocess.Popen(["java", "-jar", str(jar), "inspect", str(repo), "-o", str(target)]
                            + ([] if include_tests else ["--no-tests"]),
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def run(repo: Path | str, mode: str = "standin", with_source: bool = True, truth: Path | None = None,
        workers: int = 4, judge=None, out_root: Path = OUT, budget: Budget | None = None) -> Iterator[dict]:
    budget = budget or Budget()
    early: list[dict] = []  # events from before the output folder is known
    source = fetch.parse(str(repo), REPO_ROOT) if not isinstance(repo, Path) else repo
    if source is None:
        yield {"type": "error", "fatal": True, "message": (f"Not a folder here, and not a GitHub repository: {repo!s}. "
                                                           "Paste a local path, https://github.com/owner/repo, or owner/repo.")}
        return
    if isinstance(source, fetch.GitHubSource):
        for event in fetch.clone(source, out_root / "repos"):
            event = {"t": round(time.time() * 1000), **event}
            early.append(event)
            yield event
            if event["type"] == "error":
                return
        repo = Path(early[-1]["path"])
        name = source.folder + (("__" + source.subpath.replace("/", "_")) if source.subpath else "")
    else:
        repo = source.resolve()
        name = repo.name
    out_dir = out_root / name
    writer = Writer(out_dir)
    for event in early:
        writer.append("events", event)

    def emit(event: dict) -> dict:
        event = {"t": round(time.time() * 1000), **event}
        writer.append("events", event)
        return event

    if judge is None:
        judge, problem = make_judge(mode, out_dir, out_root)
        if problem:
            yield emit({"type": "error", "fatal": True, "message": problem})
            writer.close()
            return

    files = extract.java_files(repo, budget.include_tests)
    skipped_files = max(0, len(files) - budget.max_files)
    files = files[:budget.max_files]
    yield emit({"type": "run_start", "repo": name, "path": str(repo), "mode": mode, "judge": judge.name,
                "judge_label": judge.label, "files": len(files), "out_dir": str(out_dir), "source_sent": with_source,
                "categories": extract.CATEGORIES, "include_tests": budget.include_tests})
    if not files:
        found = ", ".join(f"{lang} ({n} files)" for lang, n in extract.languages(repo)[:4]) or "no source files"
        tests = "" if budget.include_tests else " outside test folders"
        yield emit({"type": "error", "fatal": True,
                    "message": f"No Java files{tests} in {name}. This experiment reads Java only; the repository has: {found}."})
        writer.close()
        return
    truth_file = truth or HERE / "truth" / f"{name}.graph.json"
    reference = None if truth_file.exists() else _auto_truth(repo, out_dir / "reference.graph.json", budget.include_tests)

    # 1. Syntax: milliseconds, no model.
    parser = extract.new_parser()
    parses = []
    started = time.perf_counter()
    sample = max(20, min(600, 4000 // len(files)))  # what the view draws; the counts stay exact
    for file in files:
        parsed = extract.parse_file(repo, file, parser)
        parses.append(parsed)
        record = {"file": parsed.path, "ms": round(parsed.parse_ms, 3), "nodes": parsed.node_count, "lines": parsed.lines}
        writer.append("ast", record)
        yield emit({"type": "ast", **record, "sample": parsed.categories[:sample]})
    parse_ms = (time.perf_counter() - started) * 1000
    index = TypeIndex(parses)
    types = {t.id: t for t in index.types.values()}
    yield emit({"type": "parse_done", "files": len(parses), "types": len(types), "skipped_files": skipped_files,
                "nodes": sum(p.node_count for p in parses), "ms": round(parse_ms, 2)})

    # What the judge sees: the most connected classes (entry points count extra), links among them, and
    # the communities they sit in. Everything else is still parsed, saved and drawn from syntax.
    pairs = extract.candidates(index)
    degree = {t: 0 for t in types}
    for c in pairs:
        degree[c.source] += 1
        degree[c.target] += 1
    rank = sorted(types, key=lambda t: (-(degree[t] + 3 * len(extract.endpoints(types[t]))), t))
    focus = set(rank[:budget.roles])
    judged_pairs = sorted((c for c in pairs if c.source in focus and c.target in focus),
                          key=lambda c: (-len(c.evidence), -(degree[c.source] + degree[c.target]), c.source, c.target))
    judged_pairs = judged_pairs[:budget.links]
    budget_info = {"classes": len(types), "classes_judged": len(focus), "links": len(pairs), "links_judged": len(judged_pairs)}
    yield emit({"type": "budget", "classes": len(types), "classes_judged": len(focus), "links": len(pairs),
                "links_judged": len(judged_pairs), "max_communities": budget.communities})

    entities: dict[str, dict] = {}
    relationships: dict[tuple, dict] = {}  # keyed so a later judgment updates the edge it describes

    def entity(record: dict) -> dict:
        entities[record["id"]] = record
        writer.append("entities", record)
        return emit({"type": "entity", "entity": record})

    def relationship(record: dict, update: bool = False) -> dict:
        relationships[(record["source"], record["target"], record["type"])] = record
        writer.append("relationships", record)  # an append log: the last line for an edge wins
        return emit({"type": "relationship_update" if update else "relationship", "relationship": record})

    ctx = context(name, extract.frameworks(parses))
    focus_packages = {types[t].package for t in focus}
    for package in sorted({t.package for t in types.values() if t.package}):
        yield entity({"id": f"package:{package}", "type": "PACKAGE", "title": package, "source": "tree-sitter",
                      "focus": package in focus_packages})
    for declared in types.values():
        yield entity({"id": declared.id, "type": "TYPE", "title": declared.name, "qualified_name": declared.qualified,
                      "declaration": declared.kind, "file": declared.file, "line": declared.line,
                      "annotations": [a.name for a in declared.annotations], "role": None, "source": "tree-sitter",
                      "focus": declared.id in focus})
        writer.append("text_units", {"id": f"text:{declared.id}", "entity": declared.id, "file": declared.file,
                                     "start_line": declared.line, "end_line": declared.end_line, "text": declared.source})
        if declared.package:
            yield relationship({"source": f"package:{declared.package}", "target": declared.id, "type": "CONTAINS",
                                "origin": "syntax", "confidence": 1.0, "file": declared.file, "line": declared.line})
        for parent, kind in [(p, "EXTENDS") for p in declared.extends] + [(p, "IMPLEMENTS") for p in declared.implements]:
            resolved = index.resolve(parent, declared)
            if resolved:
                yield relationship({"source": declared.id, "target": "type:" + resolved, "type": kind,
                                    "origin": "syntax", "confidence": 1.0, "file": declared.file, "line": declared.line})
        for entry in extract.endpoints(declared):
            if entry["id"] not in entities or (declared.id in focus and not entities[entry["id"]]["focus"]):
                yield entity({"id": entry["id"], "type": entry["kind"], "title": entry["name"], "source": "tree-sitter",
                              "focus": declared.id in focus})
            kind = "EXPOSES" if entry["kind"] == "ENDPOINT" else "CONSUMES"
            src, dst = (entry["id"], declared.id) if kind == "EXPOSES" else (declared.id, entry["id"])
            yield relationship({"source": src, "target": dst, "type": kind, "origin": "syntax", "confidence": 1.0,
                                "file": declared.file, "line": entry["line"], "via_method": entry["method"]})

    # Links between types that syntax proves: no model is asked whether they exist.
    proven: dict[tuple[str, str], dict[str, list]] = {}
    for c in pairs:
        proven[(c.source, c.target)] = extract.syntax_kinds(c, types[c.target])
        for kind, evidence in sorted(proven[(c.source, c.target)].items()):
            yield relationship({"source": c.source, "target": c.target, "type": kind, "origin": "syntax",
                                "confidence": 1.0, "weight": None,
                                "evidence": [{"how": e.how, "line": e.line} for e in evidence]})

    stats = {"questions": 0, "answered": 0, "cached": 0, "errors": 0, "refused": 0, "input_tokens": 0, "latency_ms": []}

    def ask_all(asks: list[Ask]) -> Iterator[tuple[Ask, object, dict]]:
        ready = []
        for ask in asks:
            problems = lint(ask)
            if problems:  # a badly formed question is never sent
                stats["refused"] += 1
                yield ask, None, emit({"type": "error", "fatal": False, "qid": ask.qid,
                                       "message": "Question refused by the check: " + "; ".join(problems)})
                continue
            ready.append(ask)
            stats["questions"] += 1
            record = {"qid": ask.qid, "phase": ask.phase, "subject": ask.subject, "files": ask.files,
                      "questions": ask.wire_questions()}
            writer.append("questions", record)
            yield ask, None, emit({"type": "question", **record})
        with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
            futures = {pool.submit(judge.ask, ask): ask for ask in ready}
            for future in as_completed(futures):
                ask, result = futures[future], future.result()
                if result.error:
                    stats["errors"] += 1
                elif result.answers is not None:
                    stats["answered"] += 1
                    stats["cached"] += int(result.cached)
                    stats["input_tokens"] += 0 if result.cached else result.input_tokens
                    if not result.cached and result.latency_ms:
                        stats["latency_ms"].append(result.latency_ms)
                record = {"qid": ask.qid, "phase": ask.phase, "subject": ask.subject, "answers": result.answers,
                          "latency_ms": round(result.latency_ms, 1), "input_tokens": result.input_tokens,
                          "cached": result.cached, "judge": result.judge, "model": result.model, "error": result.error}
                writer.append("answers", record)
                yield ask, result, emit({"type": "answer", **record})

    # Round 1: the role of each class, with its neighbourhood as context.
    inferred_roles: dict[str, dict] = {}
    role_asks = []
    for declared in (types[t] for t in rank[:budget.roles]):
        uses, used_by = neighbours(declared, pairs, types)
        role_asks.append(role_ask(declared, uses, used_by, ctx, with_source))
    for ask, result, event in ask_all(role_asks):
        yield event
        if result is None or not result.answers:
            continue
        a = result.answers
        role = a["role"]
        inferred_roles[ask.subject] = {"value": role["choice"], "confidence": round(role["confidence"], 3),
                                       "fits_a_role": round(a["fits_a_role"]["noul"], 3), "judged_by": result.judge}
        entities[ask.subject].update(role=role["choice"], role_confidence=role["confidence"],
                                     role_probabilities=role["probabilities"], fits_a_role=a["fits_a_role"]["noul"],
                                     name_misleads=a["name_misleads"]["noul"], role_judge=result.judge)
        writer.append("entities", entities[ask.subject])
        yield emit({"type": "entity_update", "entity": entities[ask.subject]})

    # Round 2: what each proven link means. The essential answer becomes the edge weight GraphRAG uses.
    weights: dict[tuple[str, str], float] = {(c.source, c.target): 0.5 for c in pairs}
    essential: dict[tuple[str, str], float] = {}
    for ask, result, event in ask_all([relation_ask(c, types, proven[(c.source, c.target)], ctx, with_source)
                                       for c in judged_pairs]):
        yield event
        if result is None or not result.answers:
            continue
        source, target = ask.subject.split("|")
        a = result.answers
        weights[(source, target)] = essential[(source, target)] = a["essential"]["noul"]
        for kind in sorted(proven[(source, target)]):
            edge = relationships[(source, target, kind)]
            yield relationship({**edge, "weight": a["essential"]["noul"], "essential": a["essential"]["noul"],
                                "judged_by": result.judge}, update=True)
        for question, kind in (("persists", "PERSISTS"), ("publishes", "PUBLISHES")):
            if a[question]["noul"] >= 0.5:
                yield relationship({"source": source, "target": target, "type": kind, "origin": result.judge,
                                    "confidence": a[question]["noul"], "weight": a[question]["noul"],
                                    "evidence": ask.state["evidence"]})
        yield emit({"type": "pair_judged", "source": source, "target": target,
                    "answers": {k: v["noul"] for k, v in a.items()}})

    # Round 3: communities are found by an algorithm and described by the judge.
    graph = nx.Graph()
    graph.add_nodes_from(types)
    for (source, target), weight in weights.items():
        if weight > 0:
            previous = graph.get_edge_data(source, target, {}).get("weight", 0)
            graph.add_edge(source, target, weight=previous + weight)
    groups = sorted((sorted(g) for g in nx.community.louvain_communities(graph, weight="weight", seed=42)),
                    key=lambda g: (-len(g), g))
    communities = [{"id": f"community:{number}", "level": 0, "members": members, "size": len(members),
                    "label": None, "judged": False} for number, members in enumerate(groups)]
    position = {t: i for i, t in enumerate(rank)}
    chosen = sorted((c for c in communities if focus & set(c["members"])),
                    key=lambda c: (-len(focus & set(c["members"])), -c["size"], c["id"]))[:budget.communities]
    asks = []
    for community in chosen:
        inside_set = set(community["members"])
        # Sorted: answers arrive in completion order, and an unsorted list would change the request - and miss the cache.
        inside, outside = [], []
        for (s, t), kinds in sorted(proven.items()):
            if s not in inside_set and t not in inside_set:
                continue
            row = {"from": types[s].name, "to": types[t].name, "kinds": sorted(kinds),
                   "essential": None if (s, t) not in essential else round(essential[(s, t)], 3)}
            (inside if s in inside_set and t in inside_set else outside).append(row)
        members = sorted(community["members"], key=lambda m: position[m])  # judged classes first
        asks.append(community_ask(community["id"], [types[m] for m in members], inferred_roles, inside, outside, ctx))
        community["judged"] = True
        yield emit({"type": "community", "community": community})
    by_id = {c["id"]: c for c in communities}
    for ask in asks:  # a group offering a single name needs no Choice: that name is the label
        if "label" not in ask.questions:
            names = list(label_candidates([types[m] for m in by_id[ask.subject]["members"]]))
            by_id[ask.subject]["label"] = names[0] if names else None
    for ask, result, event in ask_all(asks):
        yield event
        if result is None or not result.answers:
            continue
        a = result.answers
        community = by_id[ask.subject]
        if "label" in a:
            community.update(label=a["label"]["choice"], label_confidence=a["label"]["confidence"],
                             label_probabilities=a["label"]["probabilities"])
        community.update(single_theme=a["single_theme"]["noul"], business_capability=a["business_capability"]["noul"],
                         cohesion=a["cohesion"]["score"] if "cohesion" in a else None, judge=result.judge)
        yield emit({"type": "community", "community": community})
    for community in communities:
        writer.append("communities", community)

    # Assemble, grade, report.
    latencies = stats.pop("latency_ms")
    summary = {**stats, "avg_latency_ms": round(sum(latencies) / len(latencies), 1) if latencies else None,
               "est_cost_usd": round(stats["input_tokens"] * PRICE_PER_MILLION_INPUT / 1e6, 6),
               "parse_ms": round(parse_ms, 2), "ast_nodes": sum(p.node_count for p in parses),
               "entities": len(entities), "relationships": len(relationships), "communities": len(communities)}
    graphrag = {"repo": name, "judge": judge.name, "budget": budget_info, "source_sent": with_source, "context": ctx, "stats": summary,
                "entities": sorted(entities.values(), key=lambda e: e["id"]),
                "relationships": [relationships[k] for k in sorted(relationships)],
                "communities": communities}
    writer.write("graphrag.json", json.dumps(graphrag, indent=2))
    report = None
    if reference is not None:
        yield emit({"type": "reference", "stage": "waiting",
                    "message": "Waiting for the Java analyzer's reference graph to grade against..."})
        try:
            reference.wait(timeout=300)
        except subprocess.TimeoutExpired:
            reference.kill()
        truth_file = out_dir / "reference.graph.json"
    if truth_file.exists():
        report = grade(graphrag, json.loads(truth_file.read_text(encoding="utf-8")), pairs)
        writer.write("report.md", report["markdown"])
    yield emit({"type": "done", "stats": summary, "out_dir": str(out_dir),
                "grade": {k: v for k, v in report.items() if k != "markdown"} if report else None})
    writer.close()

"""Parse -> ask -> assemble, as a stream of events, saving every piece as soon as it exists.

The same generator drives the CLI and the live UI, so what the page shows is what lands on disk.
"""

from __future__ import annotations

import json
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Iterator

import networkx as nx

from . import extract
from .extract import TypeIndex
from .grade import grade
from .judges import AnswerCache, DryRunJudge, JevJudge, StandInJudge, load_env
from .questions import Ask, community_ask, relation_ask, role_ask

HERE = Path(__file__).resolve().parent.parent
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
            handle = self.handles[name] = (self.directory / f"{name}.jsonl").open("a")
        handle.write(json.dumps(record) + "\n")
        handle.flush()

    def write(self, name: str, content: str) -> None:
        (self.directory / name).write_text(content)

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


def run(repo: Path, mode: str = "standin", with_source: bool = True, truth: Path | None = None,
        workers: int = 4, judge=None, out_root: Path = OUT) -> Iterator[dict]:
    repo = repo.resolve()
    out_dir = out_root / repo.name
    writer = Writer(out_dir)

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

    files = extract.java_files(repo)
    yield emit({"type": "run_start", "repo": repo.name, "path": str(repo), "mode": mode, "judge": judge.name,
                "judge_label": judge.label, "files": len(files), "out_dir": str(out_dir), "source_sent": with_source,
                "categories": extract.CATEGORIES})
    if not files:
        yield emit({"type": "error", "fatal": True, "message": f"No .java files under {repo}"})
        writer.close()
        return

    # 1. Syntax: milliseconds, no model.
    parser = extract.new_parser()
    parses = []
    started = time.perf_counter()
    for file in files:
        parsed = extract.parse_file(repo, file, parser)
        parses.append(parsed)
        record = {"file": parsed.path, "ms": round(parsed.parse_ms, 3), "nodes": parsed.node_count, "lines": parsed.lines}
        writer.append("ast", record)
        yield emit({"type": "ast", **record, "sample": parsed.categories[:600]})
    parse_ms = (time.perf_counter() - started) * 1000
    index = TypeIndex(parses)
    types = {t.id: t for t in index.types.values()}
    yield emit({"type": "parse_done", "files": len(parses), "types": len(types),
                "nodes": sum(p.node_count for p in parses), "ms": round(parse_ms, 2)})

    entities: dict[str, dict] = {}
    relationships: list[dict] = []

    def entity(record: dict) -> dict:
        entities[record["id"]] = record
        writer.append("entities", record)
        return emit({"type": "entity", "entity": record})

    def relationship(record: dict) -> dict:
        relationships.append(record)
        writer.append("relationships", record)
        return emit({"type": "relationship", "relationship": record})

    for package in sorted({t.package for t in types.values() if t.package}):
        yield entity({"id": f"package:{package}", "type": "PACKAGE", "title": package, "source": "tree-sitter"})
    for declared in types.values():
        yield entity({"id": declared.id, "type": "TYPE", "title": declared.name, "qualified_name": declared.qualified,
                      "declaration": declared.kind, "file": declared.file, "line": declared.line,
                      "annotations": [a.name for a in declared.annotations], "role": None, "source": "tree-sitter"})
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
            if entry["id"] not in entities:
                yield entity({"id": entry["id"], "type": entry["kind"], "title": entry["name"], "source": "tree-sitter"})
            kind = "EXPOSES" if entry["kind"] == "ENDPOINT" else "CONSUMES"
            src, dst = (entry["id"], declared.id) if kind == "EXPOSES" else (declared.id, entry["id"])
            yield relationship({"source": src, "target": dst, "type": kind, "origin": "syntax", "confidence": 1.0,
                                "file": declared.file, "line": entry["line"], "via_method": entry["method"]})

    stats = {"questions": 0, "answered": 0, "cached": 0, "errors": 0, "input_tokens": 0, "latency_ms": []}

    def ask_all(asks: list[Ask]) -> Iterator[tuple[Ask, object, dict]]:
        for ask in asks:
            stats["questions"] += 1
            record = {"qid": ask.qid, "phase": ask.phase, "subject": ask.subject, "files": ask.files,
                      "questions": ask.wire_questions()}
            writer.append("questions", record)
            yield ask, None, emit({"type": "question", **record})
        with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
            futures = {pool.submit(judge.ask, ask): ask for ask in asks}
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

    # 2. Roles.
    roles: dict[str, str] = {}
    for ask, result, event in ask_all([role_ask(t, with_source) for t in types.values()]):
        yield event
        if result is None or not result.answers:
            continue
        answer = result.answers["role"]
        roles[ask.subject] = answer["choice"]
        entities[ask.subject].update(role=answer["choice"], role_confidence=answer["confidence"],
                                     role_probabilities=answer["probabilities"], role_judge=result.judge)
        writer.append("entities", entities[ask.subject])
        yield emit({"type": "entity_update", "entity": entities[ask.subject]})

    # 3. Relationships between types that mention each other.
    pairs = extract.candidates(index)
    weights: dict[tuple[str, str], float] = {(c.source, c.target): 0.5 for c in pairs}
    for ask, result, event in ask_all([relation_ask(c, types, with_source) for c in pairs]):
        yield event
        if result is None or not result.answers:
            continue
        source, target = ask.subject.split("|")
        relation, invokes = result.answers["relation"], result.answers["invokes"]
        status = "rejected" if relation["choice"] == "NONE" else ("uncertain" if relation["confidence"] < 0.6 else "accepted")
        weights[(source, target)] = 0.0 if status == "rejected" else (
            0.2 if relation["choice"] == "USES_TYPE" else relation["confidence"])
        yield relationship({"source": source, "target": target, "type": relation["choice"], "origin": result.judge,
                            "confidence": relation["confidence"], "probabilities": relation["probabilities"],
                            "invokes_probability": invokes["noul"], "status": status,
                            "evidence": ask.state["evidence"]})

    # 4. Communities: found by an algorithm, described by the judge.
    graph = nx.Graph()
    graph.add_nodes_from(types)
    for (source, target), weight in weights.items():
        if weight > 0:
            previous = graph.get_edge_data(source, target, {}).get("weight", 0)
            graph.add_edge(source, target, weight=previous + weight)
    groups = sorted((sorted(g) for g in nx.community.louvain_communities(graph, weight="weight", seed=42)),
                    key=lambda g: (-len(g), g))
    communities = []
    asks = []
    for number, members in enumerate(groups):
        cid = f"community:{number}"
        # Sorted: answers arrive in completion order, and an unsorted list would change the request - and miss the cache.
        inside = sorted(({"source": types[s].name, "target": types[t].name, "type": r["type"]} for r in relationships
                         for s, t in [(r["source"], r["target"])] if s in members and t in members and s in types and t in types),
                        key=lambda r: (r["source"], r["target"], r["type"]))
        asks.append(community_ask(cid, [types[m] for m in members], roles, inside))
        community = {"id": cid, "level": 0, "members": members, "size": len(members), "label": None}
        communities.append(community)
        yield emit({"type": "community", "community": community})
    by_id = {c["id"]: c for c in communities}
    for ask, result, event in ask_all(asks):
        yield event
        if result is None or not result.answers:
            continue
        community = by_id[ask.subject]
        community.update(label=result.answers["label"]["choice"], label_confidence=result.answers["label"]["confidence"],
                         label_probabilities=result.answers["label"]["probabilities"],
                         cohesion=result.answers["cohesion"]["score"],
                         business_capability=result.answers["business_capability"]["noul"], judge=result.judge)
        yield emit({"type": "community", "community": community})
    for community in communities:
        writer.append("communities", community)

    # 5. Assemble, grade, report.
    latencies = stats.pop("latency_ms")
    summary = {**stats, "avg_latency_ms": round(sum(latencies) / len(latencies), 1) if latencies else None,
               "est_cost_usd": round(stats["input_tokens"] * PRICE_PER_MILLION_INPUT / 1e6, 6),
               "parse_ms": round(parse_ms, 2), "ast_nodes": sum(p.node_count for p in parses),
               "entities": len(entities), "relationships": len(relationships), "communities": len(communities)}
    graphrag = {"repo": repo.name, "judge": judge.name, "source_sent": with_source, "stats": summary,
                "entities": sorted(entities.values(), key=lambda e: e["id"]),
                "relationships": sorted(relationships, key=lambda r: (r["source"], r["target"], r["type"], r["origin"])),
                "communities": communities}
    writer.write("graphrag.json", json.dumps(graphrag, indent=2))
    report = None
    truth_file = truth or HERE / "truth" / f"{repo.name}.graph.json"
    if truth_file.exists():
        report = grade(graphrag, json.loads(truth_file.read_text()), [c for c in pairs])
        writer.write("report.md", report["markdown"])
    yield emit({"type": "done", "stats": summary, "out_dir": str(out_dir),
                "grade": {k: v for k, v in report.items() if k != "markdown"} if report else None})
    writer.close()

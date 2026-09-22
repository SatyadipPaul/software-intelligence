import json
from pathlib import Path

import httpx2
import pytest

from jevgraph import extract
from jevgraph.judges import AnswerCache, JevJudge
from jevgraph.pipeline import run

HERE = Path(__file__).resolve().parent.parent
FIXTURE = HERE.parent.parent / "fixtures" / "sample-commerce"
TRUTH = HERE / "truth" / "sample-commerce.graph.json"


def events(tmp_path, **kwargs):
    return list(run(FIXTURE, out_root=tmp_path, **kwargs))


def test_candidates_cover_every_compiler_dependency():
    parses = [extract.parse_file(FIXTURE, f, extract.new_parser()) for f in extract.java_files(FIXTURE)]
    pairs = {(c.source, c.target) for c in extract.candidates(extract.TypeIndex(parses))}
    truth = json.loads(TRUTH.read_text())
    in_repo = {n["id"] for n in truth["nodes"] if n["kind"] not in ("EXTERNAL_SYMBOL",) and n["id"].startswith("type:")
               and "#" not in n["id"] and ".field:" not in n["id"]}
    depends = {(e["from"], e["to"]) for e in truth["edges"] if e["kind"] == "DEPENDS_ON" and e["from"] in in_repo and e["to"] in in_repo}
    assert depends and depends == pairs


def test_standin_run_writes_every_file_and_grades(tmp_path):
    evs = events(tmp_path, mode="standin")
    assert evs[0]["type"] == "run_start" and evs[-1]["type"] == "done"
    out = tmp_path / "sample-commerce"
    for name in ["events.jsonl", "ast.jsonl", "entities.jsonl", "relationships.jsonl", "text_units.jsonl",
                 "questions.jsonl", "answers.jsonl", "communities.jsonl", "graphrag.json", "report.md"]:
        assert (out / name).exists(), name
    grade = evs[-1]["grade"]
    assert (grade["roles"]["correct"], grade["roles"]["scored"]) == (6, 6)
    assert grade["depends_on"]["correct"] == grade["depends_on"]["truth"] == 7
    assert grade["invokes"]["correct"] == grade["invokes"]["truth"] == 6
    # every line written is valid JSON, even though it was appended while running
    for line in (out / "events.jsonl").read_text().splitlines():
        json.loads(line)


def test_dry_run_saves_one_request_per_question_and_answers_nothing(tmp_path):
    evs = events(tmp_path, mode="dryrun")
    asked = [e for e in evs if e["type"] == "question"]
    requests = [json.loads(l) for l in (tmp_path / "sample-commerce" / "requests.jsonl").read_text().splitlines()]
    assert len(requests) == len(asked) > 0
    assert all(set(r["request"]) == {"model", "state", "questions"} for r in requests)
    assert all(e["answers"] is None for e in evs if e["type"] == "answer")


def test_no_source_sends_no_source_text(tmp_path):
    events(tmp_path, mode="dryrun", with_source=False)
    lines = (tmp_path / "sample-commerce" / "requests.jsonl").read_text().splitlines()
    sent = "\n".join(json.dumps(json.loads(l)["request"]["state"]) for l in lines)  # the data about the code
    # Derived facts ("field ledger is initialised with new BillingLedger()") may go; raw source lines may not.
    for line in ["private final", "return ", "= new BillingLedger();", "public String", "audited:", "getSimpleName()"]:
        assert line not in sent, line


def test_jev_mode_without_key_or_cache_stops_with_instructions(tmp_path, monkeypatch):
    monkeypatch.delenv("TYPESAFE_API_KEY", raising=False)
    monkeypatch.setattr("jevgraph.judges.ENV_FILE", tmp_path / "absent.env")
    evs = events(tmp_path, mode="jev")
    assert evs[-1]["type"] == "error" and evs[-1]["fatal"] and ".env" in evs[-1]["message"]


class FakeJev:
    """Plays the TypeSafe API: checks each request's shape, answers every question, counts calls."""

    def __init__(self):
        self.calls = 0

    def __call__(self, request: httpx2.Request) -> httpx2.Response:
        assert request.url.path == "/v1/systemone" and request.method == "POST"
        assert request.headers["authorization"].startswith("Bearer ")
        body = json.loads(request.content)
        assert body["model"] == "jev-test" and body["state"] and body["questions"]
        self.calls += 1
        answers = {}
        for name, q in body["questions"].items():
            assert q["type"] in ("choice", "noul", "score") and q.get("instructions")
            if q["type"] == "choice":
                labels = list(q["criteria"])
                probs = {label: (0.7 if i == 0 else 0.3 / (len(labels) - 1)) for i, label in enumerate(labels)}
                answers[name] = {"type": "choice", "choice": labels[0], "confidence": 0.7, "probabilities": probs}
            elif q["type"] == "noul":
                answers[name] = {"type": "noul", "noul": 0.8}
            else:
                n = len(q["criteria"])
                answers[name] = {"type": "score", "score": 2.0, "confidence": 0.6,
                                 "legend": {str(i): c for i, c in enumerate(q["criteria"])},
                                 "probabilities": {str(i): 1.0 / n for i in range(n)}}
        return httpx2.Response(200, json={"model": "jev-test-2026", "answers": answers,
                                          "usage": {"input_tokens": 100, "output_tokens": 5}})


def test_jev_path_through_the_real_sdk_then_replays_from_cache(tmp_path):
    fake = FakeJev()

    def judge():
        return JevJudge(AnswerCache(tmp_path / "jev_cache.jsonl"), "test-key", "jev-test",
                        base_url="https://api.typesafe.ai", transport=httpx2.MockTransport(fake))

    first = events(tmp_path, judge=judge())
    answers = [e for e in first if e["type"] == "answer"]
    assert answers and all(e["answers"] and not e["cached"] and e["model"] == "jev-test-2026" for e in answers)
    assert fake.calls == len(answers)
    assert first[-1]["stats"]["input_tokens"] == 100 * len(answers)
    roles = [e["entity"]["role"] for e in first if e["type"] == "entity_update"]
    assert roles and set(roles) == {"CONTROLLER"}  # the fake always picks the first option

    graph = lambda: {k: v for k, v in json.loads((tmp_path / "sample-commerce" / "graphrag.json").read_text()).items()
                     if k != "stats"}
    before = graph()
    second = events(tmp_path, judge=judge())
    assert fake.calls == len(answers), "a second run must be served entirely from the cache"
    assert all(e["cached"] for e in second if e["type"] == "answer")
    assert graph() == before, "replaying the cache must rebuild the same graph"

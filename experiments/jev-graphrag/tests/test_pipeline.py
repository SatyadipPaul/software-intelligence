import json
from pathlib import Path

import httpx2
import pytest

from jevgraph import extract
from jevgraph import questions as q
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
    assert grade["syntax_depends_on"]["correct"] == grade["syntax_depends_on"]["truth"] == grade["syntax_depends_on"]["predicted"] == 7
    assert grade["syntax_calls"]["correct"] == grade["syntax_calls"]["truth"] == grade["syntax_calls"]["predicted"] == 6
    assert grade["persists_false_positives"] == grade["publishes_false_positives"] == 0
    assert evs[-1]["stats"]["refused"] == 0
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


def all_asks():
    parses = [extract.parse_file(FIXTURE, f, extract.new_parser()) for f in extract.java_files(FIXTURE)]
    index = extract.TypeIndex(parses)
    types = {t.id: t for t in index.types.values()}
    pairs = extract.candidates(index)
    ctx = q.context("sample-commerce", extract.frameworks(parses))
    asks = []
    for with_source in (True, False):
        for t in types.values():
            asks.append(q.role_ask(t, *q.neighbours(t, pairs, types), ctx, with_source))
        for c in pairs:
            asks.append(q.relation_ask(c, types, extract.syntax_kinds(c, types[c.target]), ctx, with_source))
    members = list(types.values())
    asks.append(q.community_ask("community:all", members, {}, [], [], ctx))
    asks.append(q.community_ask("community:one", members[:1], {}, [], [], ctx))
    return asks


def test_every_question_passes_the_check():
    asks = all_asks()
    assert len(asks) > 30
    assert [p for a in asks for p in q.lint(a)] == []


def test_questions_follow_the_primitive_rules():
    asks = all_asks()
    for a in asks:
        for name, question in a.wire_questions().items():
            if question["type"] == "choice":  # no catch-all: "none of these" is its own Noul
                assert not set(question["criteria"]) & q.CATCH_ALL
    role = next(a for a in asks if a.phase == "role")
    assert set(role.questions) == {"role", "fits_a_role", "name_misleads"}
    assert {"CONTROLLER", "SERVICE", "REPOSITORY_COMPONENT", "ENTITY", "CONFIGURATION"} <= set(q.ROLES)  # the product's EntityKind names
    relation = next(a for a in asks if a.phase == "relation")
    assert {x["type"] for x in relation.wire_questions().values()} == {"noul"}  # several can be true: one Noul each
    single = next(a for a in asks if a.subject == "community:one")
    assert "cohesion" not in single.questions  # cohesion of one class is meaningless


def test_the_check_refuses_bad_questions():
    good = all_asks()[0]
    bad = q.Ask("x", "role", "x", {"type": {}}, {
        "catch_all": q.Choice(instructions="Which?", criteria={"CONTROLLER": "a", "OTHER": "anything else"}),
        "one_sided": q.Noul(instructions="Is `type` ok?", criteria={"true": "yes"}),
        "flat": q.Score(instructions="How much?", criteria=["low", "high"]),
        "dangling": q.Noul(instructions="Is `nowhere.field` set?", criteria={"true": "a", "false": "b"}),
    }, {})
    problems = " | ".join(q.lint(bad))
    for expected in ["catch-all option 'OTHER'", "both the yes and the no", "three ordered levels", "`nowhere.field` is not in the state"]:
        assert expected in problems, expected
    assert q.lint(good) == []


def test_refused_questions_are_never_sent(tmp_path, monkeypatch):
    monkeypatch.setattr("jevgraph.pipeline.lint", lambda ask: ["forced"] if ask.phase == "relation" else [])
    evs = events(tmp_path, mode="dryrun")
    sent = [json.loads(l)["qid"] for l in (tmp_path / "sample-commerce" / "requests.jsonl").read_text().splitlines()]
    assert sent and not any(qid.startswith("relation:") for qid in sent)
    assert evs[-1]["stats"]["refused"] == 7

"""Who answers the questions: Jev, the offline rule stand-in, or nobody (dry run).

Every Jev answer is cached by a hash of exactly what was sent (model, state, questions), so a re-run
replays for free and gives the same graph - the model is not reproducible, the cache file is.
"""

from __future__ import annotations

import hashlib
import json
import os
import threading
import time
from dataclasses import dataclass
from pathlib import Path

from .questions import Ask

HERE = Path(__file__).resolve().parent.parent
ENV_FILE = HERE / ".env"


def load_env() -> dict[str, str]:
    """Read experiments/jev-graphrag/.env on every call, so a key added mid-session needs no restart."""
    values = {}
    if ENV_FILE.exists():
        for raw in ENV_FILE.read_text().splitlines():
            line = raw.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip().strip('"').strip("'")
    for key in ("TYPESAFE_API_KEY", "TYPESAFE_DEFAULT_MODEL", "TYPESAFE_BASE_URL"):
        if os.environ.get(key):
            values[key] = os.environ[key]
    return values


@dataclass
class Result:
    answers: dict | None
    latency_ms: float
    input_tokens: int
    cached: bool
    judge: str
    model: str | None = None
    error: str | None = None


class AnswerCache:
    def __init__(self, path: Path):
        self.path = path
        self.lock = threading.Lock()
        self.entries: dict[str, dict] = {}
        if path.exists():
            for line in path.read_text().splitlines():
                if line.strip():
                    entry = json.loads(line)
                    self.entries[entry["key"]] = entry

    @staticmethod
    def key(model: str, ask: Ask) -> str:
        payload = json.dumps({"model": model, "state": ask.state, "questions": ask.wire_questions()},
                             sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(payload.encode()).hexdigest()

    def get(self, key: str) -> dict | None:
        return self.entries.get(key)

    def put(self, key: str, entry: dict) -> None:
        with self.lock:
            self.entries[key] = {"key": key, **entry}
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with self.path.open("a") as out:
                out.write(json.dumps(self.entries[key]) + "\n")


class StandInJudge:
    """Annotation and syntax rules. NOT Jev: it exists so the pipeline and UI run with no key."""

    name = "standin-rules"
    label = "STAND-IN (rules, not Jev)"

    def ask(self, ask: Ask) -> Result:
        return Result(ask.stand_in, 0.0, 0, False, self.name)


class DryRunJudge:
    """Records the exact request Jev would receive and answers nothing."""

    name = "dry-run"
    label = "DRY RUN (requests saved, no answers)"

    def __init__(self, requests_file: Path, model: str):
        self.requests_file = requests_file
        self.model = model
        self.lock = threading.Lock()

    def ask(self, ask: Ask) -> Result:
        body = {"qid": ask.qid, "request": {"model": self.model, "state": ask.state, "questions": ask.wire_questions()}}
        with self.lock, self.requests_file.open("a") as out:
            out.write(json.dumps(body) + "\n")
        return Result(None, 0.0, 0, False, self.name)


class JevJudge:
    name = "jev"
    label = "JEV (live, cached)"

    def __init__(self, cache: AnswerCache, api_key: str | None, model: str, base_url: str | None = None,
                 transport=None):
        self.cache = cache
        self.model = model
        self.client = None
        self._client_args = dict(api_key=api_key, model=model, base_url=base_url, transport=transport, timeout=30.0)

    def _client(self):
        if self.client is None:
            from typesafe_sdk import TypeSafeClient
            self.client = TypeSafeClient(**{k: v for k, v in self._client_args.items() if v is not None})
        return self.client

    def ask(self, ask: Ask) -> Result:
        key = AnswerCache.key(self.model, ask)
        hit = self.cache.get(key)
        if hit is not None:
            return Result(hit["answers"], hit["latency_ms"], hit["input_tokens"], True, self.name, hit.get("model"))
        from typesafe_sdk import TypeSafeError
        started = time.perf_counter()
        try:
            response = self._client().system_one(ask.state, ask.questions)
        except TypeSafeError as error:
            return Result(None, (time.perf_counter() - started) * 1000, 0, False, self.name,
                          error=f"{type(error).__name__}: {error}")
        latency = (time.perf_counter() - started) * 1000
        answers = {name: answer.model_dump(mode="json") for name, answer in response.answers.items()}
        tokens = response.usage.input_tokens or 0
        self.cache.put(key, {"qid": ask.qid, "model": response.model, "answers": answers,
                             "latency_ms": latency, "input_tokens": tokens})
        return Result(answers, latency, tokens, False, self.name, response.model)

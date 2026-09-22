"""Local server for the live view. Binds to 127.0.0.1 only.

    python server.py            # then open http://127.0.0.1:8765

The Jev key can be pasted into the page. The page sends it here once, in a POST body. This process
keeps it in memory only: it is not written to disk, not logged, and never sent back to the page
(which is told only that a key is set and its last four characters). Stopping the server forgets it.
A key in .env or the environment still works; a pasted key wins over both.
"""

import argparse
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib.metadata import version
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from jevgraph.judges import AnswerCache, load_env
from jevgraph.pipeline import BUDGETS, OUT, Budget, run
from jevgraph.probe import probe

HERE = Path(__file__).resolve().parent
DEFAULT_REPO = "fixtures/sample-commerce"
SERVED = {"graphrag.json": "application/json", "report.md": "text/plain; charset=utf-8"}
RUN_LOCK = threading.Lock()  # two runs would write the same output files
MAX_BODY = 4096


class KeyStore:
    """The key pasted into the page, for this process's lifetime only."""

    def __init__(self):
        self._key: str | None = None
        self._lock = threading.Lock()

    def set(self, key: str | None) -> None:
        with self._lock:
            self._key = key

    def get(self) -> str | None:
        with self._lock:
            return self._key


PAGE_KEY = KeyStore()


def active_key() -> tuple[str | None, str]:
    """The key to use and where it came from: the page first, then .env or the environment."""
    if PAGE_KEY.get():
        return PAGE_KEY.get(), "page"
    env = load_env()
    return env.get("TYPESAFE_API_KEY"), ("file or environment" if env.get("TYPESAFE_API_KEY") else "none")


def valid_key(key: object) -> str | None:
    if not isinstance(key, str):
        return None
    key = key.strip()
    if not key or len(key) > 512 or not key.isascii() or any(c.isspace() or ord(c) < 32 for c in key):
        return None
    return key


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):  # nothing is logged: request bodies can hold the key
        pass

    def _send(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, payload: dict, status: int = 200) -> None:
        self._send(status, json.dumps(payload).encode(), "application/json")

    def _same_origin(self) -> bool:
        """Only this server's own page may send a key or ask for a key test: another website open in the
        browser cannot plant a key here or spend the user's quota."""
        host = self.headers.get("Host", "")
        origin = self.headers.get("Origin")
        return (origin == f"http://{host}" and host.split(":")[0] in ("127.0.0.1", "localhost")
                and self.headers.get("Content-Type", "").startswith("application/json"))

    def _body(self) -> dict | None:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            return None
        try:
            payload = json.loads(self.rfile.read(length))
        except (ValueError, UnicodeDecodeError):
            return None
        return payload if isinstance(payload, dict) else None

    def _key_status(self) -> dict:
        key, source = active_key()
        cache = AnswerCache(OUT / "jev_cache.jsonl")
        return {"key_present": bool(key), "key_source": source, "key_ends": key[-4:] if key and len(key) > 8 else None,
                "key_file": str(HERE / ".env"), "model": load_env().get("TYPESAFE_DEFAULT_MODEL", "jev-latest"),
                "cached_answers": len(cache.entries)}

    def do_GET(self):
        url = urlparse(self.path)
        query = {k: v[0] for k, v in parse_qs(url.query).items()}
        if url.path in ("/", "/index.html"):
            return self._send(200, (HERE / "ui" / "index.html").read_bytes(), "text/html; charset=utf-8")
        if url.path == "/api/status":
            return self._json({**self._key_status(), "sdk": version("typesafe-sdk"), "default_repo": DEFAULT_REPO})
        if url.path == "/api/run":
            return self._stream(query)
        parts = url.path.strip("/").split("/")
        if len(parts) == 3 and parts[0] == "out" and parts[2] in SERVED and "." not in parts[1][:1]:
            target = (OUT / parts[1] / parts[2]).resolve()
            if target.parent.parent == OUT.resolve() and target.exists():
                return self._send(200, target.read_bytes(), SERVED[parts[2]])
        self._send(404, b"not found", "text/plain")

    def do_POST(self):
        url = urlparse(self.path)
        if url.path not in ("/api/key", "/api/check-key"):
            return self._send(404, b"not found", "text/plain")
        if not self._same_origin():
            return self._json({"error": "Refused: only this server's own page may do that."}, 403)
        body = self._body()
        if body is None:
            return self._json({"error": "Expected a small JSON body."}, 400)
        if url.path == "/api/key":
            if body.get("clear"):
                PAGE_KEY.set(None)
                return self._json(self._key_status())
            key = valid_key(body.get("key"))
            if key is None:
                return self._json({"error": "That does not look like an API key (empty, too long, or has spaces)."}, 400)
            PAGE_KEY.set(key)
            return self._json(self._key_status())
        key, _ = active_key()  # /api/check-key
        if not key:
            return self._json({"ok": False, "message": "No key yet: paste it into the key box and press Use key."})
        env = load_env()
        return self._json(probe(key, env.get("TYPESAFE_DEFAULT_MODEL"), env.get("TYPESAFE_BASE_URL")))

    def _stream(self, query: dict) -> None:
        source = (query.get("path") or DEFAULT_REPO).strip()
        mode = query.get("mode", "standin")
        preset = BUDGETS.get(query.get("budget", "normal"), BUDGETS["normal"])
        budget = Budget(preset.roles, preset.links, preset.communities, query.get("tests") == "1", preset.max_files)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()

        def send(event: dict) -> None:
            self.wfile.write(f"data: {json.dumps(event)}\n\n".encode())
            self.wfile.flush()

        if not RUN_LOCK.acquire(timeout=10):
            send({"type": "error", "fatal": True, "message": "Another run is still going; try again in a moment."})
            return
        try:
            for event in run(source, mode, query.get("source", "1") != "0", budget=budget, api_key=PAGE_KEY.get()):
                send(event)
        except (BrokenPipeError, ConnectionResetError):
            return  # the page was closed or restarted the run
        except Exception as error:  # surface it in the page rather than a silent dead stream
            send({"type": "error", "fatal": True, "message": f"{type(error).__name__}: {error}"})
            raise
        finally:
            RUN_LOCK.release()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    try:
        server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    except OSError:
        print(f"Port {args.port} is in use. Try: python server.py --port 8766")
        return 1
    print(f"Jev GraphRAG live view: http://127.0.0.1:{args.port}  (Ctrl+C to stop)")
    print("Paste your Jev API key into the page; it stays in this process's memory only.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())

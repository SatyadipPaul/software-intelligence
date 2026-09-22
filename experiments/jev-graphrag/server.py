"""Local server for the live view. Binds to 127.0.0.1 only.

    python server.py            # then open http://127.0.0.1:8765

The browser never sees the API key: it asks this process to run the pipeline and receives the
events as a Server-Sent Events stream. The key is read from .env or the environment on every run.
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
from jevgraph.pipeline import OUT, run

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent.parent
DEFAULT_REPO = "fixtures/sample-commerce"
SERVED = {"graphrag.json": "application/json", "report.md": "text/plain; charset=utf-8"}
RUN_LOCK = threading.Lock()  # two runs would write the same output files


def resolve(path: str) -> Path:
    candidate = Path(path).expanduser()
    return candidate if candidate.is_absolute() else (REPO_ROOT / candidate)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):  # keep the terminal for errors
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

    def do_GET(self):
        url = urlparse(self.path)
        query = {k: v[0] for k, v in parse_qs(url.query).items()}
        if url.path in ("/", "/index.html"):
            return self._send(200, (HERE / "ui" / "index.html").read_bytes(), "text/html; charset=utf-8")
        if url.path == "/api/status":
            env = load_env()
            cache = AnswerCache(OUT / "jev_cache.jsonl")
            return self._json({"key_present": bool(env.get("TYPESAFE_API_KEY")),
                               "key_file": str(HERE / ".env"), "model": env.get("TYPESAFE_DEFAULT_MODEL", "jev-latest"),
                               "cached_answers": len(cache.entries), "sdk": version("typesafe-sdk"),
                               "default_repo": DEFAULT_REPO})
        if url.path == "/api/run":
            return self._stream(query)
        parts = url.path.strip("/").split("/")
        if len(parts) == 3 and parts[0] == "out" and parts[2] in SERVED and "." not in parts[1][:1]:
            target = (OUT / parts[1] / parts[2]).resolve()
            if target.parent.parent == OUT.resolve() and target.exists():
                return self._send(200, target.read_bytes(), SERVED[parts[2]])
        self._send(404, b"not found", "text/plain")

    def _stream(self, query: dict) -> None:
        repo = resolve(query.get("path") or DEFAULT_REPO)
        mode = query.get("mode", "standin")
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
            if not repo.is_dir():
                send({"type": "error", "fatal": True, "message": f"Not a directory: {repo}"})
                return
            for event in run(repo, mode, query.get("source", "1") != "0"):
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
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Jev GraphRAG live view: http://127.0.0.1:{args.port}  (Ctrl+C to stop)")
    print(f"API key file: {HERE / '.env'}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())

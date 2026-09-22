"""Turn what someone pasted - a local path, a GitHub URL, or owner/repo - into a local directory.

GitHub repositories are shallow-cloned once into out/repos/ and reused after that. git runs with its
arguments as a list (no shell) and with prompts disabled, so a private or missing repository fails
fast with a message instead of hanging on a password prompt.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path

NAME = r"[A-Za-z0-9_.-]+"
URL = re.compile(rf"^(?:https?://)?(?:www\.)?github\.com/({NAME})/({NAME}?)(?:\.git)?(?:/tree/({NAME})(?:/(.+?))?)?/?$")
SHORT = re.compile(rf"^({NAME})/({NAME})$")
CLONE_TIMEOUT_S = 300


@dataclass
class GitHubSource:
    owner: str
    repo: str
    ref: str | None = None
    subpath: str | None = None

    @property
    def url(self) -> str:
        return f"https://github.com/{self.owner}/{self.repo}.git"

    @property
    def label(self) -> str:
        return f"{self.owner}/{self.repo}" + (f"@{self.ref}" if self.ref else "") + (f"/{self.subpath}" if self.subpath else "")

    @property
    def folder(self) -> str:
        return "__".join(p for p in (self.owner, self.repo, self.ref) if p)


def parse(text: str, base: Path) -> Path | GitHubSource | None:
    """A local directory wins; otherwise a GitHub URL or owner/repo; otherwise None."""
    text = text.strip()
    if not text:
        return None
    local = Path(text).expanduser()
    local = local if local.is_absolute() else base / local
    if local.is_dir():
        return local
    match = URL.match(text)
    if match:
        owner, repo, ref, sub = match.groups()
        if ".." in (sub or "").split("/"):
            return None
        return GitHubSource(owner, repo, ref, sub)
    match = SHORT.match(text)
    if match and not text.startswith((".", "/")):
        return GitHubSource(*match.groups())
    return None


def clone(source: GitHubSource, cache: Path):
    """Yield progress events; the last one is {"type": "fetch", "stage": "ready", "path": ...} or an error."""
    target = cache / source.folder
    if (target / ".git").is_dir():
        yield {"type": "fetch", "stage": "cached", "source": source.label,
               "message": f"Using the copy of {source.label} cloned earlier ({_age(target)} ago)."}
    else:
        if shutil.which("git") is None:
            yield {"type": "error", "fatal": True, "message": "git is not installed, so GitHub repositories cannot be fetched."}
            return
        yield {"type": "fetch", "stage": "cloning", "source": source.label, "message": f"Cloning {source.label} (latest commit only)..."}
        cache.mkdir(parents=True, exist_ok=True)
        partial = cache / (source.folder + ".partial")
        shutil.rmtree(partial, ignore_errors=True)
        command = ["git", "clone", "--depth", "1", "--single-branch"]
        if source.ref:
            command += ["--branch", source.ref]
        command += [source.url, str(partial)]
        started = time.perf_counter()
        try:
            done = subprocess.run(command, capture_output=True, text=True, timeout=CLONE_TIMEOUT_S,
                                  env={**os.environ, "GIT_TERMINAL_PROMPT": "0"})
        except subprocess.TimeoutExpired:
            shutil.rmtree(partial, ignore_errors=True)
            yield {"type": "error", "fatal": True, "message": f"Cloning {source.label} took longer than {CLONE_TIMEOUT_S} s and was stopped."}
            return
        if done.returncode != 0:
            shutil.rmtree(partial, ignore_errors=True)
            reason = (done.stderr.strip().splitlines() or ["unknown error"])[-1]
            if "not found" in reason.lower() or "could not read username" in reason.lower():
                reason = "the repository does not exist, or it is private"
            yield {"type": "error", "fatal": True, "message": f"Could not clone {source.label}: {reason}."}
            return
        partial.rename(target)
        yield {"type": "fetch", "stage": "cloned", "source": source.label,
               "message": f"Cloned {source.label} in {time.perf_counter() - started:.1f} s."}
    path = target / source.subpath if source.subpath else target
    if not path.is_dir():
        yield {"type": "error", "fatal": True, "message": f"{source.subpath} is not a folder in {source.owner}/{source.repo}."}
        return
    yield {"type": "fetch", "stage": "ready", "source": source.label, "path": str(path)}


def _age(path: Path) -> str:
    seconds = max(0, time.time() - path.stat().st_mtime)
    return f"{seconds / 3600:.0f} h" if seconds >= 3600 else f"{seconds / 60:.0f} min"

"""Fails when the README says something the code no longer does.

A README drifts silently: nothing breaks when a flag is renamed, a module is added, or a release
moves the version on, and the first person to notice is the one who copied the stale line. Every
check here compares a claim in README.md against the thing it describes, and prints every mismatch
rather than stopping at the first.

Run after `mvn package`, from the repository root: python3 .github/scripts/readme_drift.py
"""
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
README = (ROOT / "README.md").read_text()
JAR = ROOT / "apps/cli/target/repo-intel.jar"
problems = []


def run(*args, stdin=None):
    return subprocess.run(["java", "-jar", str(JAR), *args], input=stdin, capture_output=True,
                          text=True, cwd=ROOT, timeout=300).stdout


def code_blocks(text):
    return re.findall(r"^```[^\n]*\n(.*?)^```", text, re.M | re.S)


# -- 1. Every command and flag used in an example exists --------------------------------------
root_help = run("-h")
visible = re.findall(r"^  ([a-z][a-z-]+)\s{2,}", root_help, re.M)
footer = re.search(r"for working on this tool itself: (.+)\.", root_help)
hidden = [c.strip() for c in footer.group(1).split(",")] if footer else []
known = set(visible) | set(hidden)
helps = {}

for block in code_blocks(README):
    for line in block.splitlines():
        line = line.lstrip("$ ").split(" # ")[0]
        m = re.match(r"(?:bin/)?repo-intel(?:\.jar)?\s+([a-z][a-z-]*)(.*)", line.strip()) \
            or re.match(r"java -jar \S*repo-intel\.jar\s+([a-z][a-z-]*)(.*)", line.strip())
        if not m:
            continue
        command, rest = m.group(1), re.sub(r"\([^)]*\)", "", m.group(2))  # drop shell subexpressions
        if command not in known:
            problems.append(f"example uses unknown command `{command}`: {line.strip()}")
            continue
        helps.setdefault(command, run(command, "-h"))
        for flag in re.findall(r"(?<![\w-])(--[a-z][\w-]*)", rest):
            if flag not in helps[command]:
                problems.append(f"`{command}` has no flag {flag}: {line.strip()}")

# -- 2. The command list names every command the root help shows -----------------------------
listed = set(re.findall(r"^repo-intel ([a-z][a-z-]+)", "\n".join(code_blocks(README)), re.M))
for command in visible:
    if command not in listed:
        problems.append(f"command `{command}` is in `repo-intel -h` but not in the README's command list")

# -- 3. Relative links resolve ----------------------------------------------------------------
for link in re.findall(r"\]\(((?!https?:|mailto:|#)[^)\s]+)\)", README):
    if not (ROOT / link.split("#")[0]).exists():
        problems.append(f"broken link: {link}")

# -- 4. Published coordinates carry the current version ---------------------------------------
pom = (ROOT / "pom.xml").read_text()
version = re.search(r"<artifactId>software-intelligence</artifactId>\s*<version>([^<]+)</version>", pom).group(1)
for found in re.findall(r"<version>([^<]+)</version>", README):
    if found != version:
        problems.append(f"README shows <version>{found}</version>; the project is {version}")
for found in re.findall(r"io\.github\.satyadippaul:[\w-]+:(\d[\w.-]*):", README):
    if found != version:
        problems.append(f"README invokes a plugin at {found}; the project is {version}")

# -- 5. The layout lists every module ---------------------------------------------------------
layout = re.search(r"## Repository layout\s*```text\n(.*?)```", README, re.S).group(1)
for module in re.findall(r"<module>([^<]+)</module>", pom):
    if module + "/" not in layout:
        problems.append(f"module {module} is missing from the README's repository layout")

# -- 6. The server's tool table matches what the server advertises ----------------------------
messages = "\n".join(json.dumps(m) for m in [
    {"jsonrpc": "2.0", "id": 1, "method": "initialize",
     "params": {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "drift", "version": "0"}}},
    {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
]) + "\n"
replies = [json.loads(line) for line in run("serve", "-C", "fixtures/sample-commerce", stdin=messages).splitlines()]
advertised = [tool["name"] for tool in replies[1]["result"]["tools"]]
section = re.search(r"## Serving it to an assistant(.*?)\n## ", README, re.S)
documented = re.findall(r"`([a-z_]+)`", "\n".join(
    row.split("|")[1] for row in section.group(1).splitlines() if row.startswith("| `"))) if section else []
if sorted(documented) != sorted(advertised):
    problems.append(f"README documents tools {sorted(documented)}; the server advertises {sorted(advertised)}")

if problems:
    print(f"README drift: {len(problems)} problem(s)")
    for problem in problems:
        print("  - " + problem)
    sys.exit(1)
print(f"README matches the code: {len(known)} commands, {sum(len(h) > 0 for h in helps.values())} checked "
      f"for flags, version {version}, {len(advertised)} server tools")

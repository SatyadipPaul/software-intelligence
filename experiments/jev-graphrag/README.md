# Jev GraphRAG experiment

A standalone Python experiment, outside the product. It is not wired into `repo-intel`, and it
changes nothing about the Java analyzer's guarantees.

**Question it answers:** if tree-sitter supplies the syntax and TypeSafe's Jev supplies the
judgments, how close does the resulting GraphRAG data get to what the compiler-backed Java analyzer
proves?

```text
.java files ─tree-sitter─▶ syntax facts ─────────────────────────────────────▶ graph   (no model)
                        └▶ candidate pairs + types ─▶ Jev: role? relation? community? ─▶ graph
                                                                   │
            every piece saved as it arrives ─▶ out/<repo>/*.jsonl, graphrag.json, report.md
```

Jev is asked only what syntax cannot settle:

| Judgment | Primitive | Asked about |
|---|---|---|
| Architectural role | Choice: controller, service, repository, entity, consumer, gateway, configuration, other | each type |
| Kind of relationship | Choice: depends_on, calls, creates, persists, publishes, uses_type, none | each pair where A names B in code |
| Does A invoke B's own operations? | Noul | each such pair |
| Community label, cohesion, business capability | Choice + Score + Noul | each group Louvain finds |

Declarations, imports, inheritance, HTTP routes and topic names come from tree-sitter and never
reach Jev.

## Where the API key goes

```bash
cp .env.example .env     # then set TYPESAFE_API_KEY=... in experiments/jev-graphrag/.env
```

`.env` is git-ignored and read again on every run, so the server needs no restart. Exporting
`TYPESAFE_API_KEY` in the shell works too. Set `TYPESAFE_DEFAULT_MODEL` to pin a model instead of
using `jev-latest`.

## Run it

```bash
cd experiments/jev-graphrag
python -m venv .venv && . .venv/bin/activate      # Windows: .venv\Scripts\activate
pip install -r requirements.txt

python server.py                                 # live view: http://127.0.0.1:8765
python run.py ../../fixtures/sample-commerce --mode jev      # or: the same from the command line
python -m pytest -q tests
```

In the live view, enter a repository path (relative to this repository's root, or absolute), pick
a judge and press **Start**. On the left, every AST node tree-sitter produced drifts as noise. In
the middle, particles from the file in question are pulled into the Jev filter, the answer's
probabilities fill in, and the particles leave in the colour of the answer. On the right, they land
on the graph, which builds up as the answers arrive. Syntax facts take the lower path around the
filter, because they never need a model.

| Judge | What it does |
|---|---|
| `jev` | Calls Jev through the official `typesafe-sdk`. Every answer is cached in `out/jev_cache.jsonl`, keyed by a hash of the exact request, so a re-run replays for free and rebuilds the same graph. |
| `standin` | Annotation and syntax rules. **Not Jev.** It lets you run the pipeline and the view with no key, and it sets the baseline Jev has to beat. |
| `dryrun` | Saves the exact request for every question to `requests.jsonl` and answers nothing. Use it to read what would be sent before sending it. |

## Output (GraphRAG-shaped, written incrementally)

`out/<repo>/`:

- `entities.jsonl` holds types, packages, endpoints and topics, with role and probabilities once judged.
- `relationships.jsonl` holds syntax edges (`origin: syntax`) and judged edges, with confidence,
  probabilities, `invokes_probability`, `status` (accepted, uncertain or rejected) and the evidence lines.
- `communities.jsonl` holds Louvain groups (fixed seed) with the judged label, cohesion and business-capability probability.
- `text_units.jsonl` holds each type's source span. These are what a GraphRAG retriever returns.
- `questions.jsonl`, `answers.jsonl` and `events.jsonl` form the full audit trail. `events.jsonl`
  is exactly what the live view played.
- `graphrag.json` is everything assembled, sorted for stable diffs. `report.md` is the grade.

Every line is flushed when it is written, so a run you stop keeps everything up to that point.

## How it is graded

`truth/sample-commerce.graph.json` is this repository's own Java analyzer output for the fixture
(`repo-intel inspect fixtures/sample-commerce`, with the machine path stripped). `report.md` compares:

- **Roles**, only where the compiler sees a framework role. Types with no annotation have no
  compiler answer, so the judge's reading of them is listed for a person to judge, not scored.
- **DEPENDS_ON** and **invokes**: precision and recall against compiler bindings between in-repo types.
- **Candidate coverage**: the share of real dependencies tree-sitter put in front of the judge.
  The judge cannot find a pair it is never shown.

To grade another repository, write its graph with `repo-intel inspect <repo> -o truth/<name>.graph.json`.

## What leaves your machine in `jev` mode

Each type's structure goes to `api.typesafe.ai`: names, fields, method signatures and annotation
values, plus derived facts such as "method `summary` calls `audit.record(...)`". **Its source text
and the matching code lines** go too, unless you untick *send source* (or pass `--no-source`). A
test checks that no raw source line is sent in that case.
`dryrun` shows exactly what would be sent. The local server binds to `127.0.0.1` only and never
hands the key to the browser.

## Known limits

- The `jev` path is tested against a local stand-in for the API that runs the real SDK (request
  shape, answer parsing, cache replay). It has not yet run against the live service from here,
  because the build environment could not reach `api.typesafe.ai`.
- Java only. Adding a language means a tree-sitter grammar plus that language's rules for
  declarations and references in `extract.py`.
- Syntax-level resolution: a receiver's type is resolved through fields, parameters and locals,
  not through inheritance or return types. Anything it cannot resolve is left out, never guessed.
- tree-sitter runs in Python on the local server rather than as WebAssembly in the page. Reading a
  local path and keeping the key out of the browser both need the server anyway, it is the same
  tree-sitter core at the same speed, and a single extractor means the view and the saved data
  cannot disagree.

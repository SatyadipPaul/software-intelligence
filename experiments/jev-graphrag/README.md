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

**Syntax proves what it can, and Jev is asked only the rest.** tree-sitter supplies declarations,
imports, inheritance, routes and topics, plus DEPENDS_ON (a type is named), CREATES (`new`) and
CALLS (a resolved call to a method the target declares). None of those reach Jev.

| Round | Question | Primitive | Why this shape |
|---|---|---|---|
| Role of each class | `role` | Choice | One role per class. Keys are the product's own node types (CONTROLLER, SERVICE, REPOSITORY_COMPONENT, ENTITY, CONFIGURATION) plus MESSAGE_CONSUMER, EXTERNAL_CLIENT and UTILITY, each with a description and signs to look for |
| | `fits_a_role` | Noul | A Choice always names a winner, so "none of these" is asked separately |
| | `name_misleads` | Noul | The name promises something the code does not do, for example a Repository that stores nothing |
| What each proven link means | `essential` | Noul | Does A delegate part of its job to B? This becomes the edge **weight** GraphRAG and community detection use |
| | `persists`, `publishes` | Noul each | Several can be true at once, so one Noul per relationship type, never a Choice |
| What each community is | `label` | Choice | Only names the code offers, each with where it comes from; skipped when only one name exists |
| | `single_theme` | Noul | The "no name fits" check |
| | `cohesion` | Score | Four concrete, ordered levels; skipped for a one-class group |
| | `business_capability` | Noul | A capability for users, as opposed to plumbing |

Every request carries the same `context`: repository, language, the frameworks its imports reveal,
and what graph is being built. Earlier answers reach later rounds only as `inferred_role`, never
mixed in with observed facts.

**The question check.** `lint()` runs before anything is sent, and a question that fails it is
refused, never sent. It checks that:

- every Noul defines both its yes and no outcomes;
- every Score has at least three ordered levels;
- no Choice has a catch-all option;
- every option has a description;
- every part of the state a question points at (`` `type.code` ``, `inspect: [...]`) exists in the state sent with it.

Read the exact requests before spending a token: `QUESTIONS.md` holds one real request per round
(regenerate it with `python explain_questions.py`), and `--mode dryrun` saves all of them.

## Where the API key goes

**In the page (simplest).** Start `python server.py`, open http://127.0.0.1:8765, paste the key into
**Jev API key** and press **Use key**. Press **Test key** to make one real call (the role question for
one class, about 2,000 input tokens) before a full run.

- The page sends the key once, in a request body, to the server on your own computer (127.0.0.1).
- The server keeps it in memory only: not written to disk, not logged, never sent back to the page
  (the page sees only its last four characters), and forgotten when the server stops.
- The server accepts a key only from its own page, so another website open in your browser cannot
  plant one or spend your quota.
- **remember on this computer** (off by default) keeps the key in this browser's storage so it is
  re-sent after a reload. **Forget** clears it from the server and the browser.

**Or in a file.** `copy .env.example .env` (`cp` on Mac/Linux) and set `TYPESAFE_API_KEY=...` in
`experiments/jev-graphrag/.env`. It is git-ignored and read on every run. A key pasted into the page
wins over the file. `python check_jev.py` runs the same one-call test from the terminal.

If the key is rejected or `api.typesafe.ai` cannot be reached, a run stops after the first call with
one message instead of sending every question.

## Run it

```bash
cd experiments/jev-graphrag
python -m venv .venv && . .venv/bin/activate      # Windows: .venv\Scripts\activate
pip install -r requirements.txt

python check_jev.py                              # optional: one real call to test the key in .env
python server.py                                 # live view: http://127.0.0.1:8765
python run.py ../../fixtures/sample-commerce --mode jev      # or: the same from the command line
python -m pytest -q tests
```

In the live view, paste any of these into **Repository** and press **Start**:

- a local folder (relative to this repository's root, or absolute), for example `fixtures/sample-commerce`;
- a GitHub URL such as `https://github.com/spring-projects/spring-petclinic`, optionally with
  `/tree/<branch>/<folder>`;
- or just `owner/repo`.

A GitHub repository is shallow-cloned once into `out/repos/` (git must be installed) and reused on
later runs. Private or missing repositories, and repositories with no Java, stop with a message that
says why and which languages they do contain.

On the left, every AST node tree-sitter produced drifts as noise. In the middle, particles from the
file in question are pulled into the Jev filter, the answer's probabilities fill in, and the
particles leave in the colour of the answer. On the right, they land on the graph, which builds up
as the answers arrive. Syntax facts take the lower path around the filter, because they never need
a model.

**Budget.** A real repository can have thousands of classes, so the questions are budgeted
(15, 40 or 120 classes; command line: `--budget small|normal|large|all`). The most connected
classes go first, then the links among them, then the communities that contain them. Every class
is still parsed, saved and graded as syntax; only what is sent to Jev is limited, and the page says
how much was judged. Test folders are skipped unless you tick **tests** (`--include-tests`).

**Grading any repository.** With this repository's Java analyzer built
(`mvn -q -pl apps/cli -am package -DskipTests`), each run also starts it in the background on the
same files. That gives a compiler-backed reference graph for any Java repository, not just the
fixture, and `report.md` grades against it. Without the analyzer, runs still work but aren't graded.

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
- Syntax-level resolution. Names follow Java's scoping rules (nested types, imports, same package),
  and a call's receiver is typed from fields, parameters, locals, enhanced-for and catch variables,
  `var x = new T()`, `new T().m()` and static nested types. Inherited methods resolve up the
  in-repo class hierarchy. Calls on a returned value (`a.b().c()`) and on untyped lambda parameters
  need a compiler and are left out, never guessed.

  Against the Java analyzer: DEPENDS_ON matches it on the fixture and on spring-petclinic, and is at
  96% precision / 95% recall on iluwatar/java-design-patterns. CALLS is at 99% precision everywhere,
  with 44% recall on that repository, because of the calls-on-returned-values limit above.
- tree-sitter runs in Python on the local server rather than as WebAssembly in the page. Reading a
  local path and keeping the key out of the browser both need the server anyway, it is the same
  tree-sitter core at the same speed, and a single extractor means the view and the saved data
  cannot disagree.

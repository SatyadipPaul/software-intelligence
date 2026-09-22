# Making the tool usable: where the time actually goes

Date: 2026-09-22. OpenJDK 25.0.4, 4 cores, 15 GB RAM, `-Xmx4g` unless stated. Repositories at
spring-petclinic `818c413`, jackson-databind `2ebadd9`, this repository `eebe5dd`. Every number
below is best-of-three after one discarded warm-up, measured through the shaded jar.

This document exists because the obvious fix was the wrong one. The plan that preceded it ranked a
disk cache first and a long-lived process third. Measuring reversed that order, and the reversal is
the entire content of this file.

Reproduce with:

```bash
mvn -B -DskipTests package
java -Xmx4g -jar apps/cli/target/repo-intel.jar inspect <repo> -o graph.json
java -Xmx6g -jar apps/cli/target/repo-intel.jar ask graph.json "<question>"
```

## What was measured

Cold analysis, from source, writing a canonical graph:

| repository | `.java` files | cold analyze | graph JSON |
| --- | ---: | ---: | ---: |
| `fixtures/sample-commerce` | 10 | 1.1 s | 64 KB |
| spring-petclinic | 50 | 1.9 s | 2.0 MB |
| this repository | 130 | 4.1 s | 11 MB |
| jackson-databind | 1386 | **28.0 s** | **134 MB** |

The same question asked two ways — against the source tree, and against the graph the previous
command just wrote:

| | from source | from cached graph |
| --- | ---: | ---: |
| this repository | 4.7 s | 1.7 s |
| jackson-databind | 32.5 s | **16.4 s** |

A cache halves it. That is the finding that matters, because halving it was not the expectation:
skipping a 28-second analysis should have left something close to nothing.

## Where the cached 16 seconds goes

Four commands, one 134 MB graph file, no analysis in any of them:

| command | time |
| --- | ---: |
| `impact` — parse, then a shallow walk | 4.7 s |
| `architecture` | 10.1 s |
| `index` — parse, then derive the tree | 13.2 s |
| `ask` — parse, tree, BM25, query | 15.6 s |
| `ask --index` — same, with the tree pinned to a 16 MB file | **11.2 s** |

Differencing those: roughly **4 s parsing JSON, 8 s deriving the index tree, 2 s building the BM25
index**. All three are rebuilt on every invocation, and all three are pure setup.

`--index` already exists to pin the derived tree, and it recovers only 3 of the 8 seconds, because
pinning replaces *deriving* a tree with *parsing* a 16 MB file. It is the right idea applied to a
process that cannot keep anything.

Set the floor against this project's own measurement of the work being set up for — descent on
jackson-databind is 63 ms:

> `ask` costs 11,160 ms on jackson-databind, of which retrieval is 63 ms.
> **99.4% of the command is per-process setup.**

## What that rules in and out

**A disk cache is not the answer, though it is worth having.** It attacks the 28-second analysis and
leaves an 11-second floor. On a repository the size of jackson-databind that turns an unusable tool
into a slow one.

**Process-per-command is the answer.** Nothing in the list above is expensive work; it is the same
work repeated because there is nowhere to keep the result. A process that parses once and holds the
graph, the tree and the indexes serves the second question in milliseconds.

This also reframes the format question. 134 MB of JSON for 1386 source files is heavy, and 4 seconds
to parse it is a real cost — but only once per session rather than once per question, so it stops
being the thing to fix first.

## Plan

### Phase 1 — ergonomics that do not depend on any of the above

Worth doing because they are right, not because they are fast.

- `<repo>` defaults to `.`. Every command declares it as `@CommandLine.Parameters(index = "0")`
  with no arity, so the directory you are standing in has to be typed as `.` fifteen different ways.
- A launcher, so `repo-intel` is a word rather than `java -jar repo-intel.jar`.
- The Java API documented. `RepositoryModel.build(repository, classpath, includeTests, layers)` is
  a supported entry point that appears nowhere in the README, and `Layers` is a five-argument record
  whose callers write `new Layers(true, true, 8, false, false)`.
- Maintainer commands grouped away from the five a new reader needs.

### Phase 2 — the session core

One abstraction holding `CodeGraph`, `IndexTree`, `Bm25Index` and the optional dense index, built
once and invalidated explicitly, failing *towards* re-analysis whenever the answer is unclear: a
stale graph answers questions about code that is no longer there, which is worse than a slow one.

> This originally said "keyed on git HEAD and working-tree cleanliness". It is not. Git answers a
> narrower question than the one being asked — it says nothing about a directory that is not a
> repository, and `git status` is itself a subprocess and a dependency — and the check turned out to
> be affordable done exactly: a content digest of the files the analyzer would read costs 56–168 ms
> on jackson-databind against 30 s of rebuilding. The cheaper key was not needed, so it was not used.

It is built standalone, with no interface attached, because both surfaces in Phase 3 need exactly
it. A disk cache becomes a component here — and unlike `--index` today, it persists the derived
tree and the indexes rather than the graph alone.

**Built, in `modules/session`.** Per question on jackson-databind: 11,160 ms to about 60 ms, of
which the staleness check is nearly all — see [the measurement](benchmarks/warm-session-2026-09-22.md).
Staleness is decided by digesting the content of every file the analyzer would read, not by
timestamps and sizes, because the failure timestamps permit is the one in this design that produces
no error. The disk cache is not built yet; in-process reuse was the larger half and is independent
of it.

### Phase 3 — one warm surface

| | `repo-intel shell` | `repo-intel serve` |
| --- | --- | --- |
| What it fixes | Human ergonomics; repeated questions cost milliseconds | The card-passing exchange in the README stops being manual |
| What it costs | A line-editing dependency, which under this project's licensing rule means a licence read and an allow-list entry before any code is written | A new module and a protocol surface |

The `navigate` design — pin a tree, print cards, accept only ids that appeared on them — exists so
that an assistant can drive the descent without being able to assert anything. It is currently
carried out by a human copying cards between a terminal and a chat window. A server is what that
design was already shaped for; the shell is the smaller win on the same foundation.

**Built: `repo-intel serve`.** The server was chosen, and heap was measured before it was written:
a warm session on jackson-databind retains 286 MB but needs 1 GB to analyse, and a refresh does not
raise that floor. Over the protocol each question after the first takes 74–102 ms. No dependency
was added — the protocol's JSON is a strict RFC 8259 codec in `apps/cli`, in the same spirit as the
graph reader — so nothing new reached the allow-list or the notices.

### Phase 4 — the on-disk format, if it still matters

Re-measure after Phase 2. A warm process may have made it irrelevant.

## Risks, stated in advance

- **A new dependency is not a detail here.** Everything on the distribution path passes an enforcer
  allow-list and appears in `THIRD-PARTY-NOTICES.md`. Phase 3's line-editing library needs its
  licence read while the phase is being scoped, not while it is being written.
- **Heap for a long-lived process is unmeasured.** A shallow `impact` over the jackson-databind
  graph runs in 2 GB. A process holding graph, tree, BM25 and dense indexes for a large repository
  has no measured ceiling yet, and a server without a stated limit is a server that dies at 3 a.m.
  *Since measured:* 1 GB to analyse jackson-databind, 286 MB retained warm; see
  [the measurement](benchmarks/warm-session-2026-09-22.md). The dense index is still unmeasured,
  because the session does not hold one yet.
- **A commit is invisible to the fingerprint.** With `--commit-vocabulary`, the graph reads git
  history, and `git commit` changes that history without changing a byte of source. A server started
  that way keeps the commit vocabulary it began with until some source file changes. Found while
  wiring `serve`; stated here rather than papered over, because commit vocabulary is off by default
  and the fix — folding `HEAD` into the fingerprint when that layer is on — is small but not free.
- **Cache invalidation is the part that can be silently wrong.** Every other item on this list fails
  loudly.

## What this does not measure

The corpora are three repositories, the largest 1386 files. Analysis is super-linear in neither an
obvious nor a measured way across that range, and nothing here says what a 20,000-file repository
does. The conclusion — that setup dominates work — gets stronger with size rather than weaker, so
the plan does not depend on that gap, but the numbers should not be extrapolated into claims.

Timings are wall-clock on one machine, including JVM startup (~0.4 s), which is part of what a user
waits for and is deliberately not subtracted.

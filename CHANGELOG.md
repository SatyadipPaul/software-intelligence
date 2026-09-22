# Changelog

Notable changes to this project, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Every number quoted here comes from a benchmark in [`docs/benchmarks/`](docs/benchmarks/) that says
how to reproduce it. Where a result is negative, or contaminated, or unverified, it says so — that is
the point of quoting it rather than a headline.

## [Unreleased]

Nothing yet.

## [0.3.0] — 2026-09-22

Everything here landed after `v0.2.0` was published to Maven Central.

### Added — `repo-intel serve`, for assistants
- **A Model Context Protocol server over stdio**, holding one repository warm. Tools: `ask`,
  `impact`, `context`, `navigate_start`/`navigate_choose` and `status`. On jackson-databind the
  first question waits for the analysis, about 20 s; every later one takes 74–102 ms over the
  protocol, against 11 s from the command line with a cached graph.
- **The navigate exchange is native.** The card-by-card descent that previously meant a person
  copying cards between a terminal and a chat window is two tool calls, and a finished descent
  returns the verified answer directly. Ids that were not on the cards are still refused.
- **It never answers from a graph it knows is stale.** Each call checks the source first and
  rebuilds if it moved, saying so in the answer; if the rebuild fails, the call fails. Open descents
  are discarded on a rebuild, because their cards describe the previous graph.
- **No new dependency.** The protocol's JSON is a strict RFC 8259 codec written for it, so nothing
  reached the allow-list or `THIRD-PARTY-NOTICES.md`.
- **Heap was measured before the server was written.** A warm session on jackson-databind retains
  286 MB and needs a 1 GB heap to analyse; a refresh does not raise that floor.

### Added — a warm session
- **`modules/session`**, holding a graph and everything derived from it across many questions.
  Per question on jackson-databind this is 11,160 ms to about 60 ms — a command-line invocation
  spends 20 s analysing, 10 s deriving the index tree and 1 s building the retrieval index to do
  63 ms of work, and none of that is expensive work, only repeated work. Measured in
  [`docs/benchmarks/warm-session-2026-09-22.md`](docs/benchmarks/warm-session-2026-09-22.md).
  Nothing uses it yet: it is the foundation the interactive and server surfaces both need, built
  on its own so that it does not end up buried inside whichever arrives first.
- **`SourceFingerprint` decides staleness from content, not timestamps.** Sizes and modification
  times are near-free and wrong in the direction that matters: an editor that restores an mtime, a
  checkout that preserves one, or a same-length edit inside one filesystem tick each yield
  "unchanged" about source that changed, after which answers describe code that is no longer there
  and nothing says so. Digesting the bytes costs 56–168 ms against 30 s of rebuilding. When the
  question cannot be answered at all, the answer is "stale".
- **`JavaRepositoryAnalyzer.sourceFiles` is now public**, so the fingerprint asks about exactly the
  files the analyzer parses rather than keeping a second copy of the rule that could drift from it.
- **A failed rebuild leaves the session stale, not falsely current.** The new fingerprint and graph
  are published together, only once the build succeeds, so a build that throws keeps the old graph
  *and* keeps reporting it stale.

### Added — a launcher, and a documented Java API
- **`bin/repo-intel` and `bin/repo-intel.cmd`**, attached to each release beside the jar. Download
  them into one directory on `PATH` and the command is `repo-intel ask "..."`. The launcher prefers
  `JAVA_HOME` when it is new enough and falls back to `java` on `PATH` when it is not — a stale
  `JAVA_HOME` previously produced `LinkageError occurred while loading main class`, which names
  neither the cause nor the fix. When no JDK 25 is found it says which Java each candidate was.
- **A "Using it as a Java library" section in the README**, which had none, although every module
  is published. Its examples are compiled against the built jars rather than written from memory.
- **`RepositoryModel.build(Path)`** — every layer, no classpath, one argument.
- **Withers on `RepositoryModel.Layers`**, so a caller writes
  `Layers.all().withCommitVocabulary(true)` rather than `new Layers(true, true, 8, false, false)`,
  where nothing says which layer is which or that `8` is a depth. A negative workflow depth is now
  rejected where it is written instead of silently finding no workflows.

### Added — release engineering

- **The README is checked against the code on every push.** `.github/scripts/readme_drift.py` fails
  CI when an example uses a command or flag that does not exist, a command is missing from the list,
  a link is broken, a published coordinate carries the wrong version, a module is missing from the
  layout, or the server's tool table disagrees with what `serve` advertises.
- **Every command is exercised in every argument form inside `mvn verify`**, rather than only by CI
  steps after the build, so a command that fails as wired fails the local build.

### Changed — the repository argument is optional
- **Every command defaults to the current directory.** `repo-intel impact PaymentService` is now
  the short form of `repo-intel impact . PaymentService`, and `-C/--repo` names a repository you
  are not standing in. Which of two positionals is which is decided by how many there are, never by
  inspecting the filesystem, so `impact PaymentService` means the same thing in a checkout that
  happens to contain a directory of that name. Every previously documented invocation still works
  unchanged.
- **`navigate` decides by flag rather than by count**, because its question is optional and the
  count alone cannot settle it: `--choose`/`--choices` mark a continuation, which carries no
  question, so a lone argument beside them is the repository and a lone argument without them is
  the question.
- **`-h` works on subcommands.** `mixinStandardHelpOptions` was set only on the root, so
  `repo-intel impact --help` answered `Unknown option: '--help'`.
- **`evaluate` and `quantize-model` are hidden from the root listing**, and named in its footer.
  They develop this tool rather than use it, and were competing with `ask` in a flat list of
  fifteen. Both still run exactly as before.

### Fixed

- **Choosing a card's heading said it was never shown.** The root heading is printed on the first
  card, has no symbol behind it, and was rejected with "not on the cards" — false, and an invitation
  to try again. It now says it is a heading and to choose a child.
- **`repo-intel -V` printed nothing.** The jar now carries its version.
- **Ambiguous-symbol warnings can go somewhere other than stderr**, which a server's client never
  sees; there they travel inside the answer.
- **The Central publishing plugin is current again**, 0.5.0 to 0.11.0. Releasing 0.2.0 uploaded the
  bundle successfully and then failed the build reading the portal's reply, because the API had
  grown a `warnings` field that 0.5.0 deserializes strictly and does not know. The upload is the
  irreversible half of a release, so failing after it is the worst place to fail: the deployment was
  staged and waiting while CI reported a red build, and the step that attaches the runnable jar to
  the GitHub release never ran. Pinning a stale version of this plugin is not a neutral choice.

## [0.2.0] — 2026-09-21

Everything here landed after `v0.1.0` was published to Maven Central.

### Added — retrieval that navigates structure

Three new modules — `index-tree`, `embedding` and `embedding-model` — and three new commands:
`index`, `navigate` and `quantize-model`.

- **Ask a question in words, not by name.** `TREE`, `DENSE` and `DENSE_HYBRID` join the existing
  `BM25` and `HYBRID` modes. Retrieval returns symbols that can be traversed and cited rather than
  line ranges that still have to be located.
- **A navigable index tree** derived from the graph's containment — repository, module, capability
  or package, type, member — with oversized sibling sets grouped rather than truncated, and
  fingerprinted against the graph it came from. `navigate` walks it one card at a time so an
  assistant can choose the branches, and may only answer with ids it was shown.
- **Tier 0 contextual cards**: the first sentence of each declaration's Javadoc, capped at 240
  characters. This was the first intervention that moved a number, taking subject-free retrieval
  from 0.180 to 0.304.
- **An optional dense encoder**, plain Java with no native runtime, reading Model2Vec static
  weights from safetensors. `potion-base-32M` scores 0.522 on the subject-free corpus and
  `potion-base-8M` 0.466.
- **int8 quantisation** (`repo-intel quantize-model`), which makes a model roughly four times
  smaller with no measurable change in ranking: on 200 questions exactly one rank moved.
- **Test-code demotion**, which is a correction rather than tidying: on a testing framework the test
  tree genuinely *means* testing, and demoting it took junit5 subject-free recall@1 from 0.065 to
  0.239.
- **Commit-message vocabulary** (`--commit-vocabulary`), off by default: it reads git history rather
  than the source at the analysed commit, needs a full clone, and refuses a shallow one. Where it
  runs it is the first Tier 0 addition since Javadoc that pays, 0.493 to 0.507 subject-free.
- **Test-name vocabulary** (`--test-vocabulary`), off by default because it measured **negative**.
  Kept and kept runnable, because a negative result nobody can re-run is not a result.

### Added — evaluation

- **The corpus grew from 38 questions to 200** across the same four repositories, keyed to source
  names and `file:line` rather than to graph ids. Every answer was read out of the source at the pinned commit. 161 of the 200
  never name the symbol they ask about, which is what the corpus is for.
- **`evaluate`** scores structural accuracy, evidence recall, anchor recall, anchor coverage,
  recall@k and MRR, and can run a naive text-search baseline over the same questions for comparison.
  precision@k is deliberately not reported, and the report says why.
- **An oracle chooser**, an instrument rather than a mode: it knows each question's answer and
  always descends towards it, which separates "the chooser picked wrong" from "the answer was not
  reachable at all". It found that the tree is almost never the limitation — the answer is reachable
  97–98% of the time and the best automatic chooser reaches 27–56% of it.

### Added — release engineering

- **`CHANGELOG.md`, `RELEASING.md`, `NOTICE` and `THIRD-PARTY-NOTICES.md`**, none of which existed
  when 0.1.0 was published.
- **A tag-triggered release workflow** that publishes from CI rather than a workstation, refuses a
  tag that is not on `main` or does not match the project version, stages rather than
  auto-publishing, and attaches the runnable shaded jar to the GitHub release — `apps/cli` publishes
  a thin jar, so every README example saying `java -jar repo-intel.jar` referred to a file no
  release produced.
- **An enforcer allow-list of every dependency on the distribution path**, so a new one fails the
  build until someone has read its licence and added it to the inventory.

### Fixed — licensing of what 0.1.0 published

- **Every dependency on the distribution path is open source and audited**, listed with version,
  licence and source in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md). No such inventory
  shipped with 0.1.0.
- **The root POM's enforcer holds an allow-list.** A new dependency, direct or transitive, on the
  compile or runtime path fails the build until someone has read its licence and added it.
- **The shaded jar states its own terms.** `META-INF/LICENSE`, `META-INF/NOTICE` and
  `META-INF/THIRD-PARTY-NOTICES.md` are the aggregate's, and every dependency's own licence text is
  preserved where it already lived. **The published 0.1.0 jar does not have this**: its
  `META-INF/LICENSE` is whichever file won the shading race, in practice JNA's, which opens by naming
  the LGPL. That coordinate cannot be replaced, so the correction lands here.
- **JNA's dual licence is elected to Apache-2.0**, so nothing bundled is under a licence that
  reaches beyond its own files. The Eclipse components are EPL-2.0, redistributed unmodified, with
  source available at the same coordinates.
- **The optional weights artifact refuses to build without a licence.** `modules/embedding-model`
  will not package model weights unless the model's own `LICENSE` and a `PROVENANCE.md` sit beside
  them. No weights are committed to this repository.

### Measured

Subject-free retrieval — the 161 of 200 questions that describe what code does rather than naming
it — over the life of this release:

| Change | subject-free |
| --- | ---: |
| baseline: identifiers only | 0.180 |
| Tier 0 Javadoc cards | 0.304 |
| dense encoder | 0.404 |
| test-code demotion | **0.522** |

Questions that *do* name their subject went from 38 of 39 to 39 of 39 and stayed there:
nothing was traded for the gains above.

### Known limitations

Stated here rather than discovered later.

- **The corpus is four open-source repositories, all of them documented.** The enterprise profile
  this design targets — uncommented business logic with a large test suite — is not represented and
  cannot be measured here. Three of this project's open conclusions depend on a corpus nobody has.
- **The questions were written by the same author as the system.** The Javadoc results are clean
  because the Javadoc is upstream-authored, but anything this project generates and then scores is
  contaminated by construction, and the branch-summary experiment is the warning: it produced a
  result that had to be reversed.
- **The descent delivers about a third of what its own structure permits.** The ceiling is 0.97 and
  the best automatic chooser reaches 0.27–0.56. That gap is where the next release lives.
- **A blind assistant benchmark is missing.** Someone other than the question author has to
  navigate, or an agent has to do it without the answer in context.
- **Semantic enrichment generates nothing.** The verification gate is built — claims are checked
  against the graph and anything uncited is withheld — but generation is an interface with no
  implementation, because shipping one would require model credentials and outbound calls.
- **SCIP ingestion is not implemented**, and Spring Boot auto-configuration and property files are
  not read, so a bean contributed only by a starter is invisible.
- **The packaged weights artifact is not published.** Its licence has not been verified from the
  model's own sources; see [`modules/embedding-model/README.md`](modules/embedding-model/README.md).

### Negative results kept on the record

Things that were built, measured, and found not to work. They are documented rather than deleted,
because the measurement is the value — and one earlier "met" was withdrawn on the strength of them:

- Branch summaries on the index tree, and their blind re-run.
- Build classpaths, which lift resolution by twenty points and move no retrieval metric at all,
  because the questions that fail are failing on vocabulary rather than on resolution.
- The index tree itself at scale, which tied flat retrieval rather than beating it — the exit
  criterion for that milestone is recorded as not met, and the earlier claim that it was met is
  withdrawn in the roadmap.
- Score-level max-passage, which came out worse than either of its inputs.
- Element-wise max and size-blind centroid aggregation of subtree representations.
- Test method names as Tier 0 prose.

## [0.1.0] — 2026-09-11

The first release to Maven Central, cut before this changelog existed. Its contents are the
repository at [`v0.1.0`](https://github.com/SatyadipPaul/software-intelligence/releases/tag/v0.1.0);
they are not reconstructed here, because a changelog written after the fact from a diff is a guess
dressed as a record.

Two defects in what it published are fixed in the next release rather than in it, since a coordinate
on Central cannot be replaced: the shaded jar's `META-INF/LICENSE` carried JNA's file rather than the
project's, and no third-party inventory shipped with it. Both are listed under Fixed above.

[Unreleased]: https://github.com/SatyadipPaul/software-intelligence/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/SatyadipPaul/software-intelligence/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/SatyadipPaul/software-intelligence/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/SatyadipPaul/software-intelligence/releases/tag/v0.1.0

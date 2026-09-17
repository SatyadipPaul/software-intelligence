# Changelog

Notable changes to this project, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Every number quoted here comes from a benchmark in [`docs/benchmarks/`](docs/benchmarks/) that says
how to reproduce it. Where a result is negative, or contaminated, or unverified, it says so — that is
the point of quoting it rather than a headline.

## [Unreleased]

Nothing yet.

## [0.1.0] — unreleased

First public release. The date is set when the tag is cut.

### Added — the deterministic core

- **`repo-intel`, a local-first command line** with fifteen commands: `inspect`, `impact`,
  `context`, `architecture`, `ask`, `index`, `navigate`, `snapshot`, `diff`, `evaluate`,
  `enrichment-plan`, `visualize`, `quantize-model`, `enrich-targets` and `enrich-apply`. No service,
  no upload, and no model credentials for anything deterministic.
- **A canonical, provenance-bearing code graph.** Every node and edge carries the resolver that
  produced it, a confidence, and a file, line and column. A fact that could not be resolved stays
  explicitly unresolved rather than being guessed.
- **Compiler-grade Java semantics** through Eclipse JDT: cross-file calls, overloads,
  implementations, fields, constructors, method references and library types when a build classpath
  is supplied. Measured resolution: 99.82% on spring-petclinic, 99.96% on spring-petclinic-rest,
  99.18% on jackson-databind, 98.82% on junit5.
- **Classpath discovery for Maven and Gradle**, the latter verified against a build with Gradle
  Isolated Projects enabled.
- **Framework interpretation for Spring and JPA**: controllers, services, repositories, beans,
  configuration properties, security guards, entities, tables, transactions, Kafka listeners, HTTP
  endpoints and `@Query` table links, each emitted with provenance.
- **Architecture summaries**: modules, workflows, business capabilities, communities
  (connected-components and k-core behind one strategy interface), PageRank and degree centrality,
  and a risk score that explains itself factor by factor and is discounted by the weakest confidence
  on the evidence path.
- **Reproducible exports.** Graph JSON, snapshots and the index tree are byte-for-byte stable across
  runs and declare a schema version. `diff` compares a snapshot against the working tree and ranks
  what changed by risk.
- **Self-contained visualization.** An interactive HTML view that loads no script from anywhere,
  plus GraphML, DOT and Cytoscape JSON.
- **A Maven plugin** with `analyze` and `impact-check` goals that reuse the build's own classpath.

### Added — retrieval

- **Ask a question in words, not by name.** `BM25`, `TREE`, `HYBRID`, `DENSE` and `DENSE_HYBRID`
  retrieval over the graph's own vocabulary; retrieval returns symbols that can be traversed and
  cited rather than line ranges that still have to be located.
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

- **200 grounded questions across four repositories**, keyed to source names and `file:line` rather
  than to graph ids. Every answer was read out of the source at the pinned commit. 161 of the 200
  never name the symbol they ask about, which is what the corpus is for.
- **`evaluate`** scores structural accuracy, evidence recall, anchor recall, anchor coverage,
  recall@k and MRR, and can run a naive text-search baseline over the same questions for comparison.
  precision@k is deliberately not reported, and the report says why.
- **An oracle chooser**, an instrument rather than a mode: it knows each question's answer and
  always descends towards it, which separates "the chooser picked wrong" from "the answer was not
  reachable at all". It found that the tree is almost never the limitation — the answer is reachable
  97–98% of the time and the best automatic chooser reaches 27–56% of it.

### Added — licensing and supply chain

- **Every dependency on the distribution path is open source and audited**, listed with version,
  licence and source in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).
- **The root POM's enforcer holds an allow-list.** A new dependency, direct or transitive, on the
  compile or runtime path fails the build until someone has read its licence and added it.
- **The shaded jar states its own terms.** `META-INF/LICENSE`, `META-INF/NOTICE` and
  `META-INF/THIRD-PARTY-NOTICES.md` are the aggregate's, and every dependency's own licence text is
  preserved where it already lived.
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

[Unreleased]: https://github.com/SatyadipPaul/software-intelligence/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/SatyadipPaul/software-intelligence/releases/tag/v0.1.0

# Execution roadmap

## Milestone 0 — deterministic foundation (now)

- Canonical repository IR with source provenance.
- Java AST ingestion and CLI JSON export.
- Fixture repository for repeatable development.
- Local filesystem operation without Docker or external services.

**Exit criterion:** a source relationship always links back to a file location and confidence level.

## Milestone 1 — compiler-grade Java semantics (in progress)

- Build Maven/Gradle classpaths.
- Resolve JDT bindings for cross-file calls, overloads, implementations, fields, constructors, and library types when a Maven/Gradle classpath is supplied.
- Import SCIP data when available; reconcile it into canonical identities.
- Maven plugin classpath discovery and local `analyze`/`impact-check` goals.
- Add schema versioning and graph snapshots. Done: exports declare a schema version, and a snapshot is reproducible byte for byte.

**Current result (schema 0.2):** Spring Petclinic resolves 99.82% of call/type edges with its Maven classpath, and 41.6% with no classpath at all — the syntax-only figure rose from 1.45% because supertypes, constructors, and in-batch source bindings are now resolved rather than emitted as unresolved placeholders. Spring Petclinic REST resolves 99.96%. The Maven plugin's production-only graph of Petclinic now contains zero unresolved edges.

**Done since:** interface dispatch normalization, overrides and exception flow, Spring bean wiring,
and build classpath discovery for Maven and Gradle, the latter verified against a build with
Isolated Projects enabled. SCIP ingestion remains open.

## Milestone 2 — framework and operational model

- Infer Spring controllers, services, repositories, beans, configuration properties, security guards, JPA entities, Kafka producers/consumers, and REST clients.
- Add endpoints, tables, topics, and external services as first-class entities.

**Exit criterion:** trace an HTTP endpoint through service, persistence, and event relationships with evidence.

**Status:** met. Controllers, services, repositories, entities, tables, transactions, endpoints,
topics, security guards, configuration properties, external services, Kafka producers, and `@Query`
table links are all emitted with provenance. Spring Boot auto-configuration and property files are
not read, so a bean contributed only by a starter is still invisible.

## Milestone 3 — architecture and impact intelligence

- Introduce deterministic community and dependency clustering behind a pluggable interface (Leiden, k-core, connected components).
- Detect workflows and calculate direct, transitive, endpoint, configuration, and event impact.
- Define an explainable risk score; no opaque model required.

**Exit criterion:** `impact symbol` provides a ranked, source-backed affected surface.

**Status:** met. `Communities` ships connected-components and k-core behind one strategy interface;
Leiden can be added and compared on the same graph. `Centrality` provides PageRank and degree.
`Workflows` traverses every endpoint and topic to the tables and topics it touches. `RiskScore`
returns a factor-by-factor explanation, discounted by the weakest confidence on the evidence path.

## Milestone 4 — evaluation before GraphRAG

- Curate 200+ grounded questions across 3–5 Java repositories. **Met: 200 questions across four** -
  25 on the fixture, 50 on spring-petclinic, 70 on jackson-databind, 55 on junit5 - keyed to source
  names and file:line rather than to graph ids, so they survive identity changes. Every one was read
  out of the source at the pinned commit rather than from tool output, and every one passes the
  traversal harness (25/25, 50/50, 70/70, 55/55, all metrics 1.000). **161 of the 200 never name the
  symbol they ask about**, and that is what the corpus is for: questions that name their subject are
  answered 38/39 = 0.974, questions that describe it instead are answered 29/161 = 0.180, falling
  from 0.529 on a ten-class fixture to 0.022 on junit5. Retrieval here is an exact-name matcher, and
  the old corpus could not show it because most of its questions said the name out loud. See
  [the 200-question corpus](benchmarks/corpus-200-2026-09-16.md).
- Measure structural accuracy, evidence recall, groundedness, latency, index cost, and token cost.
  **Precision now measured** on questions that declare an exhaustive answer, and every report states
  the answer size, because recall alone scored 1.000 against an answer of 7,946 symbols. Ranking
  quality was the next missing metric, and the one that matters for large repos. **Now measured**
  by `evaluate --retrieval-only`, which asks each question in words with no subject supplied and
  scores recall@k, MRR, and anchor recall over the anchors retrieval returned - the traversal
  harness above resolves the subject by name, so it never measured retrieval at all. precision@k is
  deliberately not reported: see Milestone 6. **Anchor coverage** now scores how much of a plural
  answer the anchor set itself reaches, which is the first metric here that measures anchoring on a
  set rather than on one symbol.
- Baseline against grep/BM25/vector RAG and a deterministic graph-only path. **A naive text-search
  baseline now runs on the same questions** (`evaluate --baseline`): the graph scores 1.000 recall
  against 0.258 on the fixture, 0.000 on Petclinic, and 0.450 on jackson-databind. A vector baseline
  still needs an embedding model that runs locally without credentials.

**Exit criterion:** every claimed quality improvement has a reproducible benchmark result.

**Generalization:** the analyzer is now exercised on jackson-databind (Maven, no framework, extreme
generics) and junit5 (Gradle Kotlin DSL, 27 source roots), which found four defects invisible on
Spring Petclinic. See [the generalization benchmark](benchmarks/generalization-2026-08-10.md).
Both are now also measured **with build classpaths**, which lifts resolution to 99.18% and 98.82%
from 77.01% and 78.78% - and moves no retrieval or traversal metric at all, because the questions
that fail are failing on vocabulary rather than on resolution. See
[the classpath run](benchmarks/classpath-2026-09-16.md).
Both now have grounded question sets - 70 for jackson-databind, 55 for junit5, the large majority of
which do not name their subject - and both are scored for retrieval in
[the 200-question corpus](benchmarks/corpus-200-2026-09-16.md). Both are also analyzed with build
classpaths, at 99.18% and 98.82% resolution.

## Milestone 5 — selective semantic enrichment and verified answers

- Rank enrichment candidates using centrality, ambiguity, downstream impact, query likelihood, and token cost.
- Add explicit token/dollar budgets and audit every enrichment decision.
- Generate claims linked to graph evidence; verify citations before presenting an answer.

**Exit criterion:** answers identify their supporting symbols and source ranges; unsupported claims are withheld or labeled.

**Status:** the verification half is done and is the half that had to be. `VerifiedAnswer` checks
every claim against the graph and withholds anything whose citation is missing, non-existent, or
unrelated to the symbol under discussion; `EnrichmentPlanner` ranks and budgets candidates and
prints an audit. Generation is an interface with no implementation: shipping one would require model
credentials and outbound calls, which the local-first invariant makes optional by definition.

## Milestone 6 — retrieval that navigates structure (built, measured on four repositories)

- Derive a navigable index tree from the graph's own containment: repository, module, capability or
  package, type, member. **Done:** `modules/index-tree` and `repo-intel index`, deterministic,
  fingerprinted against its graph, with oversized sibling sets grouped rather than truncated.
- Navigate that tree to a **set** of anchors rather than narrowing to one subject, so questions with
  plural answers stop being unanswerable by construction. **Done:** `ask --retrieval TREE|HYBRID
  --anchors N`, with per-anchor packets merged and each claim anchored on the anchor its own edge
  touches.
- Keep the deterministic navigator model-free and the assistant navigator an offline file exchange,
  as the enrichment loop already is. **Done:** the default chooser calls nothing, and `repo-intel
  navigate` presents one card per step and rejects any id that was not on it. A vector baseline is
  still blocked on a local embedding model; this path needs none.
- Rank tree branches for enrichment, so a budget buys summaries where they steer the most descents.
  **Done:** `enrich-targets --branches` and `enrichment-plan --branches` rank by reach, choices, and
  how little the card already says, through the existing budget, packet and verification path. On
  jackson-databind a 2,000-token budget buys summaries of `type`, `util`, `misc` and `deser.impl` -
  the packages a descent has to guess at - where symbol ranking spends the same budget on
  `ObjectMapper#readValue`, three `Map#get` overrides and a test utility. **Measured twice, and the
  answer is no.** Hand-written summaries appeared to lift Petclinic's anchor recall 0.737 to 0.842,
  but the same author wrote the summaries and the questions. Repeated cleanly on jackson-databind
  and junit5 - questions committed first, summaries extracted from each project's own
  package-info.java javadoc - 26 summaries moved not one metric and recovered not one question. A
  summary helps exactly when it happens to carry the words the asker uses; independent authorship
  makes that coincidental, and it did not occur once. See
  [the retrieval baseline](benchmarks/retrieval-2026-09-09.md).

**The descent had never been measured with anything but one scorer.** `modules/evaluation` did not
reference `Chooser` at all, so every number below came from `TreeNavigator.DETERMINISTIC`. Measured
against an oracle that always descends towards the answer, the tree reaches it **97-98%** of the
time on junit5, jackson-databind and Petclinic, where the deterministic chooser reaches 27-56%. The
tree is almost never the limitation; the chooser is the whole of it, and the architecture has about
three times more in it than has ever been extracted. See
[the chooser ceiling](benchmarks/chooser-ceiling-2026-09-16.md).

**Exit criterion:** tree-navigated retrieval beats flat BM25 on recall@k, MRR, and anchor recall
across all four question sets — or is dropped, having been measured rather than assumed.

**Status: not met, and the earlier "met" is withdrawn.** On 53 questions `HYBRID` was never worse
than flat retrieval and better on two sets. On the 200-question corpus that does not hold: it wins
outright only on spring-petclinic, splits on the fixture (ahead on MRR and coverage, behind on
anchor recall), and is indistinguishable from flat retrieval on jackson-databind and junit5, where
every gap is a single question. The old result was an artifact of a corpus in which most questions
named their subject and both modes scored 1.000, leaving nothing to separate them.

**What survives the larger corpus.** Anchor coverage - the only metric that scores a set rather than
one symbol - is clearly better under descent wherever it is non-zero (0.246 against 0.031 on
Petclinic, 0.321 against 0.143 on the fixture), and the descent stays the cheaper path at scale
(53 ms against 59 on jackson-databind, 38 against 47 on junit5, reading ~20 cards where flat ranking
scores 45,595 nodes). **`--retrieval` therefore still defaults to `HYBRID`** - cheaper, ahead on
coverage, behind by one question on two sets - but it is no longer described as beating flat
retrieval. CI still fails if either mode regresses against flat retrieval on the fixture.

**The bottleneck is not the index, and that is now shown rather than inferred.** Four measurements
came back negative - branch summaries, their blind re-run, build classpaths, and the tree itself at
scale - and each enriched the structure around identifiers while leaving identifiers the only
vocabulary retrieval can match. Questions that name their subject were answered 0.974 of the time;
questions that describe it, 0.180.

**Tier 0 contextual cards moved it.** Recording the first sentence of each declaration's Javadoc -
no model, no network, fully deterministic - took subject-free retrieval from **0.180 to 0.304**,
with junit5 going 0.022 to 0.239. The Javadoc is upstream-authored on three of the four
repositories, so unlike the summary experiment it is not the same hand writing the enrichment and
the questions. It also exposed a design tension: the gain favours flat retrieval, because
`CardIndex` scores a node as its whole subtree and dilutes a single doc sentence across a package.
**The obvious repair for that tension does not work.** Scoring a node as the maximum of its pooled
subtree and its best single card - max-passage, the standard remedy - came out worse than *either*
input alone on jackson-databind (anchor recall 0.271, against 0.300 pooled and 0.314 best-card),
because the two quantities are not on a comparable scale: a best-card score carries no penalty for
how much of the branch you would have to search to find that card. Reverted, with the finding
recorded in the code so it is not retried blind. A calibrated blend would need a constant, and
fitting one on four repositories that are all OSS framework code is the overfitting this corpus is
already flagged for. See [Tier 0 contextual cards](benchmarks/tier0-context-2026-09-16.md),
[subtree dilution](benchmarks/subtree-dilution-2026-09-16.md) and its
[sequel over representations](benchmarks/subtree-aggregation-2026-09-17.md),
[the 200-question corpus](benchmarks/corpus-200-2026-09-16.md) and
[the retrieval baseline](benchmarks/retrieval-2026-09-09.md).

**precision@k was the wrong ask.** Each grounded question declares one relevant symbol, so
precision@k cannot exceed 1/k and would measure that cap rather than the ranking. The harness reports
recall@k, MRR, and anchor recall, and says in its own output why the fourth metric is absent.

**Design:** [a hybrid of the code graph and PageIndex RAG](hybrid-index-tree.md).

## Milestone 7 — closing the register gap

Milestone 6's exit criterion was not met, and the 200-question corpus said why in one line:
retrieval answers **0.974** of questions that name their subject and **0.180** of questions that
describe it instead. Tier 0 contextual cards moved the second number to 0.304. This milestone is
about that number and nothing else.

[Prior work](research-grounding.md) calls this **register mismatch** and reports it at the same
magnitude elsewhere — 96–100% recall on formal-register queries against 36–44% on plain-register
ones. Four of the five things measured here are established results, and one of the negative results
is explained by a 2020 paper. The sequence below is ordered by that reading, cheapest and
most-unblocking first.

**1. Contamination is now a permanent caveat, not a solvable step.** Adopting an externally
authored evaluation set was the plan, and it is not available to this project. Everything measured
here is therefore written by the same hand that wrote the code, and that cannot be fixed by effort —
only bounded by method. The bound that works is the one the blind javadoc run used: enrich from
text the question author never read. Where even that is impossible, a number is reported as a
contaminated ceiling and is never sufficient grounds to change a default. Stating this once, here,
is worth more than repeating the caveat per experiment.

**2. Finish Tier 0 with prose the repository already contains.** Javadoc was one source and it moved
subject-free retrieval 0.180 → 0.304. Two more cost nothing and need no model: **commit messages**,
which are human prose in the asker's register already tied to files — one paper retrieves on them
alone and reports up to 80% over a BM25 baseline — and **test method names**, which in Java are
near-sentences (`shouldRejectPaymentWhenBalanceIsInsufficient`). Both matter most on exactly the
enterprise profile where Javadoc is absent, which is the profile the current corpus lacks.

**3. Choose an encoder under the distribution constraint.** Every locally-runnable graph-RAG system
has converged on the same point — 384 dimensions, 22–33M parameters — and everything larger is an
API this project cannot use. Below that market default sit **static** embeddings, which have no
forward pass at all: a vocabulary-to-vector table, tokenize and mean-pool, a few hundred lines of
pure Java with no native dependency, no ONNX and no per-platform artifacts, at ~8–30 MB. That
removes the largest open risk in this step, which was runtime feasibility rather than size. Measure
a retrieval-tuned static model, the smallest static model, and the market default as a quality
reference; ship one, as a separate optional artifact, with the core degrading to lexical scoring.
See [encoder selection](encoder-selection.md) — every figure in it is unverified, because the model
hosts are blocked here.

**4. Only then, the dilution fix. Done, and it says the dilution hypothesis was wrong.** The
max-passage repair failed *because it was applied to scores*; PARADE's finding is that aggregating
passage **representations** beats aggregating passage **scores**. `DenseTreeIndex` now folds a
subtree into one vector in a single post-order pass, with three aggregators behind `--chooser`. The
size-weighted mean wins, and both alternatives are worse: an element-wise maximum saturates over
thousands of unit vectors and collapses to 0.018 on junit5, while a size-blind centroid is the best
chooser measured on spring-petclinic (0.600) and a third of the mean on junit5 (0.073). Removing
size-weighting does not remove dilution, it trades it for the opposite distortion, and on a deep
tree the opposite distortion is worse. The dense-versus-lexical gap therefore has some other cause,
and step 6's ceiling of 0.97 is unaffected. See
[subtree aggregation](benchmarks/subtree-aggregation-2026-09-17.md).

**5. Tier 1, with a verification gate rather than trust.** Generated descriptions are the doc2query
family, whose documented failure modes are exactly the risks already recorded here. Doc2Query--
supplies the mitigation: filter generated text through a relevance model before indexing. That is
this repository's existing claims gate applied to enrichment, and it is a requirement rather than an
enhancement. Measuring it runs into step 1's permanent caveat, and the branch-summary experiment is
the warning: the same author writing both the enrichment and the questions produced a result that
had to be reversed.

**6. Read the closest prior art and record the deltas.** RANGER builds a repository knowledge graph
by AST parsing, LLM-assisted description generation and embedding — our three tiers, in our order —
and splits queries into code-entity and general, which is our named/subject-free split renamed.
LARGER formalises `HYBRID`'s thesis and criticises graph retrieval for fragmenting the agent loop
with separate traversal stages, which is a fair charge against `navigate`.

**Exit criterion:** plain-register retrieval improves on the Tier 0 baseline of 0.304, measured on
all 200 questions and reported with its contamination status stated — or the approach is dropped,
having been measured rather than assumed. The same standard as Milestone 6.

**Status: met on quality, open on packaging.** A dense encoder takes subject-free retrieval from
0.304 to **0.404**, with questions that name their subject at 39/39. `DENSE_HYBRID` — named subject
first, dense second, flat hits last — is the **first mode to beat flat retrieval on every corpus on
every headline metric**, which is the criterion Milestone 6 set and `HYBRID` failed. The encoder is
an optional module, the ONNX runtime an optional dependency of it, and the weights are supplied by
the operator: without one, every existing mode behaves exactly as before. **The encoder to ship is now measured too.** Model2Vec's static models have no forward pass at all -
a vocabulary-to-vector table, no ONNX, no JNI, no per-platform artifacts - and `potion-base-32M`
reaches **0.410**, matching the transformer reference's 0.404 while indexing jackson-databind 37x
faster (494 ms against 18.4 s); `potion-base-8M` reaches 0.379 at a quarter of the disk and 120x
faster. No encoder dominates on every corpus, and the spread between encoders is smaller than the
spread between any of them and lexical retrieval: **which** encoder is a second-order choice, having
one is the first-order one. **Demoting test code lifts it further, to 0.522.** Ranking by meaning ranks a test of X near a
question about X - correctly and uselessly - and on a testing framework that buried the API:
`DenseIndex` now demotes test nodes with the weight and detection `BranchEnrichment` already used,
taking junit5 from 0.217 to 0.391 and jackson-databind from 0.333 to 0.483. Subject-free retrieval
has gone **0.180 → 0.304 → 0.404 → 0.522** across Tier 0 Javadoc, the encoder, and this - 2.9x the
lexical baseline, with named-subject questions still at 39/39. **int8 quantization makes it shippable and costs nothing measurable**: symmetric per-row
quantization takes `potion-base-32M` from 124 MB to **32.6 MB** with subject-free retrieval
unchanged at 0.522, and `potion-base-8M` from 30 MB to **7.7 MB** at 0.466. Exactly one question in
200 changed rank, and only within the anchor set - which is precisely why it was re-benchmarked
rather than reasoned about: the 0.4% weight bound says the vectors barely moved and says nothing
about whether the order did. See [the dense encoder run](benchmarks/dense-encoder-2026-09-16.md) and
[the static encoders](benchmarks/static-encoders-2026-09-16.md).

**Environment note:** step 6, and verification of every figure in
[encoder selection](encoder-selection.md), need `arxiv.org` and the model hosts, which this
session's network policy denies. They are blocked on egress, not on effort.

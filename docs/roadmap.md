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

- Curate 200+ grounded questions across 3–5 Java repositories. **Started:** 18 questions across two
  repositories ship in `evaluation/`, keyed to source names and file:line rather than to graph ids,
  so they survive identity changes. Now 28 questions across three, including jackson-databind, which
  has no framework at all, and junit5, which is Gradle multi-module. Now 36 questions across four.
  The harness, scoring, and CI gate exist; the corpus does not yet.
- Measure structural accuracy, evidence recall, groundedness, latency, index cost, and token cost.
  **Precision now measured** on questions that declare an exhaustive answer, and every report states
  the answer size, because recall alone scored 1.000 against an answer of 7,946 symbols. Ranking
  quality was the next missing metric, and the one that matters for large repos. **Now measured**
  by `evaluate --retrieval-only`, which asks each question in words with no subject supplied and
  scores recall@k, MRR, and anchor recall over the anchors retrieval returned - the traversal
  harness above resolves the subject by name, so it never measured retrieval at all. precision@k is
  deliberately not reported: see Milestone 6.
- Baseline against grep/BM25/vector RAG and a deterministic graph-only path. **A naive text-search
  baseline now runs on the same questions** (`evaluate --baseline`): the graph scores 1.000 recall
  against 0.258 on the fixture, 0.000 on Petclinic, and 0.450 on jackson-databind. A vector baseline
  still needs an embedding model that runs locally without credentials.

**Exit criterion:** every claimed quality improvement has a reproducible benchmark result.

**Generalization:** the analyzer is now exercised on jackson-databind (Maven, no framework, extreme
generics) and junit5 (Gradle Kotlin DSL, 27 source roots), which found four defects invisible on
Spring Petclinic. See [the generalization benchmark](benchmarks/generalization-2026-08-10.md).
Both now have grounded question sets - 10 questions for jackson-databind, 8 for junit5 - and both
are scored for retrieval in [the retrieval baseline](benchmarks/retrieval-2026-09-09.md). Answer
quality on them is measured only at those sizes; the 200-question target above is what would settle
it.

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
  `ObjectMapper#readValue`, three `Map#get` overrides and a test utility. Whether real summaries
  improve descent is not yet measured: that needs an enricher run, which needs credentials the
  local-first invariant makes optional.

**Exit criterion:** tree-navigated retrieval beats flat BM25 on recall@k, MRR, and anchor recall
across all four question sets — or is dropped, having been measured rather than assumed.

**Status:** met for `HYBRID`, not for `TREE` alone. Across all four question sets, `HYBRID` is never
worse than flat retrieval and better on two: the fixture (MRR 0.950 against 0.833) and junit5 (0.938
against 0.875). `TREE` alone loses a question on junit5, so `--retrieval` still defaults to `BM25`.
At scale the descent is also the cheaper path — 101 ms against 273 ms on jackson-databind — because
it reads 18 cards where flat ranking scores 45,595 nodes. Running the real corpora found three
defects the fixture never could, each recorded in
[the retrieval baseline](benchmarks/retrieval-2026-09-09.md).

**precision@k was the wrong ask.** Each grounded question declares one relevant symbol, so
precision@k cannot exceed 1/k and would measure that cap rather than the ranking. The harness reports
recall@k, MRR, and anchor recall, and says in its own output why the fourth metric is absent.

**Design:** [a hybrid of the code graph and PageIndex RAG](hybrid-index-tree.md).

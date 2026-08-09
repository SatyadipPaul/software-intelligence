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
and build classpath discovery for Maven and Gradle layouts. SCIP ingestion remains open.

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
  so they survive identity changes. The harness, scoring, and CI gate exist; the corpus does not yet.
- Measure structural accuracy, evidence recall, groundedness, latency, index cost, and token cost.
- Baseline against grep/BM25/vector RAG and a deterministic graph-only path. BM25 over the symbol
  vocabulary ships and is measurable today; a vector baseline needs an embedding model, which is
  deferred until one can run locally without credentials.

**Exit criterion:** every claimed quality improvement has a reproducible benchmark result.

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

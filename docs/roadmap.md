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
- Add schema versioning and graph snapshots.

**Current result:** Spring Petclinic improved from 1.45% to 98.86% resolved call/type edges with its Maven classpath.

**Remaining exit work:** interface dispatch normalization, overrides/exceptions, Gradle classpath discovery, and Spring bean wiring.

## Milestone 2 — framework and operational model

- Infer Spring controllers, services, repositories, beans, configuration properties, security guards, JPA entities, Kafka producers/consumers, and REST clients.
- Add endpoints, tables, topics, and external services as first-class entities.

**Exit criterion:** trace an HTTP endpoint through service, persistence, and event relationships with evidence.

## Milestone 3 — architecture and impact intelligence

- Introduce deterministic community and dependency clustering behind a pluggable interface (Leiden, k-core, connected components).
- Detect workflows and calculate direct, transitive, endpoint, configuration, and event impact.
- Define an explainable risk score; no opaque model required.

**Exit criterion:** `impact symbol` provides a ranked, source-backed affected surface.

## Milestone 4 — evaluation before GraphRAG

- Curate 200+ grounded questions across 3–5 Java repositories.
- Measure structural accuracy, evidence recall, groundedness, latency, index cost, and token cost.
- Baseline against grep/BM25/vector RAG and a deterministic graph-only path.

**Exit criterion:** every claimed quality improvement has a reproducible benchmark result.

## Milestone 5 — selective semantic enrichment and verified answers

- Rank enrichment candidates using centrality, ambiguity, downstream impact, query likelihood, and token cost.
- Add explicit token/dollar budgets and audit every enrichment decision.
- Generate claims linked to graph evidence; verify citations before presenting an answer.

**Exit criterion:** answers identify their supporting symbols and source ranges; unsupported claims are withheld or labeled.

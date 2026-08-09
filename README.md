# Software Intelligence

An evidence-first, Java-first repository intelligence engine. It is designed to become a verified software model—code, framework, architecture, domain, runtime, and change impact—not merely a code graph or an LLM wrapper.

The current 0.1 slice is executable and intentionally evidence-first. It builds a deterministic Java graph with provenance for every relationship, recognizes Java/Spring operational concepts, resolves calls proven from in-repository declarations, and runs source-backed change-impact analysis. This gives the project a trustworthy base before adding graph storage, optional LLM enrichment, or a UI.

## Quick start

Prerequisites: JDK 25 and Maven 3.9+.

```powershell
mvn -q verify
java -jar apps/cli/target/repo-intel.jar inspect fixtures/sample-commerce -o outputs/sample-commerce.graph.json
java -jar apps/cli/target/repo-intel.jar impact fixtures/sample-commerce PaymentService --depth 3
```

The second command writes a graph containing nodes and edges with `resolver`, `confidence`, `file`, `line`, and `column` evidence.

The impact command walks only evidence-bearing incoming relationships. For the fixture it proves that removing `PaymentService` affects its controller call site and the `POST /payments/authorize` endpoint.

To inspect any Java repository:

```powershell
java -jar apps/cli/target/repo-intel.jar inspect C:\path\to\repository --output repo-graph.json
```

## Repository layout

```text
apps/cli/                  Runnable `repo-intel` command-line interface
modules/model/             Canonical graph schema and provenance model
modules/analyzer-java/     Deterministic Java source analysis using Eclipse JDT
fixtures/sample-commerce/  Small checkout flow for local smoke testing
docs/architecture.md       Product architecture and invariants
docs/roadmap.md            Sequenced implementation roadmap and exit criteria
```

## Design principles

- Deterministic analysis first; LLMs are optional downstream consumers.
- Every relation is source-backed, and unresolved facts stay explicitly unresolved.
- Retrieve minimum sufficient evidence rather than entire files or communities.
- Treat impact analysis and verified answers as product capabilities built on the same model.

## Current coverage and boundary

Implemented deterministic facts include Java files/packages/types/methods/fields/imports/inheritance, call edges resolvable from declared in-repository field types, Spring controllers/services/repositories/entities/configuration, HTTP mapping methods, transactions, Kafka listeners, and entity tables. A resolved edge is marked `INTRA_REPOSITORY_SYMBOL`; relationships that need a classpath or runtime model remain explicitly `JDT_AST_UNRESOLVED`.

The next semantic increment is Maven/Gradle classpath-aware JDT binding resolution. The project will not label that capability as complete until it is implemented and benchmarked.

See [architecture](docs/architecture.md) and the [roadmap](docs/roadmap.md) for the implementation path.

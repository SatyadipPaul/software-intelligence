# Architecture

## Product boundary

This repository implements a software-intelligence model for Java-first engineering teams. It does not attempt to be a generic chatbot or a graph visualizer. Its foundational responsibility is to construct a deterministic, inspectable, provenance-bearing model that downstream features can trust.

## Local-first invariant

The analyzer, graph, evidence, reports, cache, and optional semantic enrichment all run on local disk by default. Docker, a hosted graph database, a hosted control plane, and external LLM APIs are never required for core functionality. A repository can be analyzed in an air-gapped environment with no source-code upload.

Distribution targets are therefore native/local artifacts: a standalone JAR or native executable, a Maven plugin that runs inside the customer build, and later a Gradle plugin. Containers may be offered as an optional CI convenience, but the product must never depend on them.

## Layers

```text
Java repository
  -> deterministic ingestion (JDT AST + resolved build classpath)
  -> canonical code IR (nodes, edges, provenance)
  -> framework inference (Spring, JPA, Kafka, Security, HTTP clients)
  -> architecture inference (modules, centrality, communities, workflows, capabilities)
  -> query planner, BM25 retrieval, minimum-sufficient evidence packets, token budgets
  -> claim verification; optional LLM enrichment feeds this gate rather than bypassing it
```

Each arrow is one-way. A layer reads what the layers beneath it proved and adds its own nodes and
edges under its own resolver name; nothing above the Java layer may rewrite or delete what the
compiler established. That is what makes `--no-framework` and `--no-architecture` meaningful rather
than cosmetic: switching a layer off removes its claims and leaves the evidence untouched.

## Current implementation: vertical slice 0.1

`modules/model` defines the stable canonical IR. Every node and edge carries a provenance record: resolver, confidence, file, line, and column. The graph is index-backed, so building it is linear in nodes and edges; the same relationship recorded twice at the same source position is stored once, while two call sites on different lines stay separate evidence.

`modules/analyzer-java` parses source with Eclipse JDT and emits files, packages, imports, types (classes, interfaces, enums, records, annotation types, anonymous classes), fields, methods, inheritance relationships, calls, constructor calls, and method references. With a Maven/Gradle classpath supplied, it resolves bindings across the complete source batch (`JDT_BINDING`, confidence 1.0); without one, it still resolves calls through in-repository declared field types (`INTRA_REPOSITORY_SYMBOL`, confidence 0.98). All remaining calls and external types stay explicitly `JDT_AST_UNRESOLVED`.

### Identity and determinism

Method identity is the owning type plus the erased parameter types, so overloads never collapse into one symbol; type identity is the erased qualified name, so a generic declaration and every call into it agree on one id. A node created by a *reference* never overwrites the node created by the symbol's own *declaration*, which keeps node provenance pointing at where a symbol is defined rather than at whichever file happened to be parsed first.

Nothing machine-specific enters the graph: the repository id is the directory name and the absolute path is an attribute. Files are analyzed in sorted path order, compilation units are merged in that order rather than in parser-completion order, and attribute keys are serialized sorted. Two runs over the same source therefore produce byte-identical JSON, which is what makes benchmark deltas trustworthy.

The same analyzer recognizes Spring stereotypes, HTTP mapping methods, transaction boundaries, Kafka listeners, and JPA entity-table relationships without loading the application or calling an LLM. `GraphQueries.impact` computes callers, exposed endpoints, configuration, and event relationships from those recorded edges, returning the precise evidence path rather than an opaque risk assertion.

`apps/cli` exposes that model through `repo-intel inspect`, writing portable JSON. The JSON is the contract for the next graph store, API, and evaluation harness—not an internal debug dump.

## Invariants

- A relation is never emitted without source evidence.
- Unresolved is a valid state, never silently represented as resolved.
- An ambiguous resolution is reported as ambiguous; it is never silently narrowed to one arbitrary candidate.
- The same source produces the same graph on any machine, in any parse order. This extends to every
  layer above the analyzer: any collection whose iteration order reaches the graph must be ordered
  explicitly, because `Set.of` and `Map.of` randomize iteration per JVM run.
- A view of the graph adds no dependency on a network: no CDN, no external script or font.
- Deterministic analysis works with no LLM credentials, Docker, or outbound data transfer.
- Enrichment can add claims but cannot overwrite deterministic evidence.
- The graph model precedes GraphRAG; GraphRAG is one consumer.

These are executable claims, not prose: `modules/model` and `modules/analyzer-java` each carry tests that assert them, and CI additionally diffs three exports of the same fixture, which is deliberately built to contain every layer.

## Planned module boundaries

| Module | Responsibility | Status |
| --- | --- | --- |
| `model` | Canonical schema and provenance | Implemented |
| `analyzer-java` | JDT syntax and classpath-aware symbols | Implemented; DI inference next |
| `repo-intel-maven-plugin` | Maven-resolved classpath, local graph/report goals | Implemented |
| `framework-spring` | Spring, JPA, Kafka, Security, HTTP clients | Implemented |
| `pipeline` | Layer composition and classpath discovery | Implemented |
| `architecture` | Modules, centrality, communities, workflows, capabilities, risk | Implemented |
| `query-engine` | Retrieval, planning, budgets, answer verification | Implemented; generation is an unimplemented interface |
| `evaluation` | Grounded Java-repository benchmark | Harness implemented; corpus at 18 of 200+ questions |
| `visualization` | Self-contained views and interchange formats | Implemented |

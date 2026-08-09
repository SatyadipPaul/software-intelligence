# Architecture

## Product boundary

This repository implements a software-intelligence model for Java-first engineering teams. It does not attempt to be a generic chatbot or a graph visualizer. Its foundational responsibility is to construct a deterministic, inspectable, provenance-bearing model that downstream features can trust.

## Layers

```text
Java repository
  -> deterministic ingestion (JDT AST; Maven/Gradle metadata next)
  -> canonical code IR (nodes, edges, provenance)
  -> framework and architecture inference
  -> query planner and minimum-sufficient evidence packets
  -> optional LLM enrichment and verified answers
```

## Current implementation: vertical slice 0.1

`modules/model` defines the stable canonical IR. Every node and edge carries a provenance record: resolver, confidence, file, line, and column.

`modules/analyzer-java` parses source with Eclipse JDT and emits files, packages, imports, types, fields, methods, inheritance relationships, and method calls. It deterministically resolves calls where the receiver is a field with a declared type in the same repository (`INTRA_REPOSITORY_SYMBOL`, confidence 0.98); all other calls and external types remain explicitly `JDT_AST_UNRESOLVED` until a classpath-aware semantic resolver upgrades them.

The same analyzer recognizes Spring stereotypes, HTTP mapping methods, transaction boundaries, Kafka listeners, and JPA entity-table relationships without loading the application or calling an LLM. `GraphQueries.impact` computes callers, exposed endpoints, configuration, and event relationships from those recorded edges, returning the precise evidence path rather than an opaque risk assertion.

`apps/cli` exposes that model through `repo-intel inspect`, writing portable JSON. The JSON is the contract for the next graph store, API, and evaluation harness—not an internal debug dump.

## Invariants

- A relation is never emitted without source evidence.
- Unresolved is a valid state, never silently represented as resolved.
- Deterministic analysis works with no LLM credentials or outbound data transfer.
- Enrichment can add claims but cannot overwrite deterministic evidence.
- The graph model precedes GraphRAG; GraphRAG is one consumer.

## Planned module boundaries

| Module | Responsibility | Status |
| --- | --- | --- |
| `model` | Canonical schema and provenance | Implemented |
| `analyzer-java` | JDT syntax then classpath-aware symbols | AST baseline implemented |
| `framework-spring` | Spring, JPA, Kafka, Security edges | Planned |
| `architecture` | Communities, workflows, impact traversal | Planned |
| `query-engine` | Query classification and evidence packets | Planned |
| `enrichment` | Budgeted LLM semantic claims | Planned |
| `evaluation` | Grounded Java-repository benchmark | Planned |

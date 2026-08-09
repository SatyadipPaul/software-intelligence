# Spring Petclinic benchmark

> The tables below are the schema `0.1` run. Method identity, edge deduplication, and type
> resolution all changed in schema `0.2`, so those counts are not comparable to a current run.
> See [Schema 0.2 re-baseline](#schema-02-re-baseline) at the end of this document.

Run date: 2026-08-09  
Repository: `spring-projects/spring-petclinic`  
Repository commit: `88e37c1`  
Analyzer build: `software-intelligence` commit after class-level request-mapping support

## Method

The repository was cloned with `git clone --depth 1`. The analyzer ran locally with Temurin JDK 25.0.4 and the shaded CLI JAR. No Docker, application startup, database, hosted service, or LLM credential was used.

Commands:

```powershell
java -jar apps/cli/target/repo-intel.jar inspect <spring-petclinic> -o spring-petclinic.graph.json
java -jar apps/cli/target/repo-intel.jar impact <spring-petclinic> VetRepository --depth 3
```

## Results

| Measurement | Result |
| --- | ---: |
| Java files | 49 total / 30 main / 19 test |
| Indexing and JSON export, syntax-only | 1.08 seconds |
| Graph nodes | 1,268 |
| Graph edges | 2,424 |
| Resolved in-repository call edges, syntax-only | 22 |
| Explicitly unresolved call/type edges, syntax-only | 1,491 |
| Resolution rate, syntax-only | 1.45% |
| HTTP endpoints detected | 17 |
| Controllers detected | 6 |
| JPA entities detected | 6 |
| Database tables inferred | 6 |
| Transaction boundaries detected | 9 |

The repository's own first Maven compile on the same local JDK took 84.24 seconds, including dependency resolution. Our source indexing completed in about 1.3% of that elapsed time. This is an operational comparison, not a claim that the analyzer replaces compilation.

## Classpath-aware run

The repository classpath was generated locally with:

```powershell
mvn dependency:build-classpath -Dmdep.outputFile=target/repo-intel.classpath -Dmdep.includeScope=test
```

That classpath was passed to the CLI using `--classpath`. JDT then resolved the complete source batch and its dependencies:

| Measurement | Result |
| --- | ---: |
| Indexing and JSON export | 7.28 seconds |
| Graph nodes | 867 |
| Graph edges | 2,580 |
| `JDT_BINDING` edges | 1,652 |
| `INTRA_REPOSITORY_SYMBOL` edges | 0 |
| `DEPENDS_ON` architecture edges | 178 |
| Explicitly unresolved edges | 17 |
| Resolution rate | 98.98% |

The extra time is the cost of compiler-grade bindings and architecture dependency extraction; it remains fully local and produces high-confidence impact evidence.

## Verified impact examples

With classpath resolution, `VetRepository` produced seven direct callers and three transitive impacts, including `VetController.findPaginated`, both exposed routes, and test callers. Every call carried resolver confidence 1.00. `VetController` produced two exposed routes. The analyzer also composes class-level mappings, for example:

```text
@RequestMapping("/owners/{ownerId}")
@GetMapping("/pets/new")
→ GET /owners/{ownerId}/pets/new
```

## Findings

What is already effective:

- Fast local indexing with no service startup or source upload.
- Useful structural inventory across application and test code.
- Evidence-backed Spring route, transaction, entity, table, and impact facts.
- In-repository resolution for calls whose receiver type is declared in source.

The highest-value remaining gap is Spring dependency-injection semantics: the compiler can resolve a field's declared interface and methods, but it does not prove which runtime bean implementation Spring selects. The next milestone is interface dispatch normalization, bean wiring, and Maven/Gradle classpath discovery inside the Maven plugin.

## Maven plugin run

The plugin was installed to the local Maven repository and invoked against the same checkout without editing Petclinic's POM:

```powershell
mvn io.softwareintelligence:repo-intel-maven-plugin:0.1.0-SNAPSHOT:analyze
mvn io.softwareintelligence:repo-intel-maven-plugin:0.1.0-SNAPSHOT:impact-check '-DrepoIntel.symbol=VetRepository'
```

The plugin uses Maven's resolved project classpath and excludes test sources by default:

| Measurement | Result |
| --- | ---: |
| Plugin analysis wall time | 14.42 seconds |
| Production nodes | 380 |
| Production edges | 753 |
| `JDT_BINDING` edges | 328 |
| `DEPENDS_ON` architecture edges | 117 |
| Explicitly unresolved edges | 16 |
| `VetRepository` direct impacts | 2 |
| `VetRepository` transitive impacts | 3 |

The direct production callers were `VetController.findPaginated` and `VetController.showResourcesVetList`; the transitive impacts included `showVetList`, `GET /vets`, and `GET /vets.html`.

The plugin also emitted `target/repo-intel/impact-report.sarif`, with one source location per impacted node for CI code-scanning ingestion.

## Minimum-sufficient context packet

The new context query was run against `VetRepository` with the same classpath. It emitted 13 caller nodes, 2 endpoint nodes, 1 dependency node, and 20 evidence edges. This packet is deliberately smaller than the 867-node repository graph and is suitable as a grounded input to a later reasoning layer.

## Schema 0.2 re-baseline

Same repository commit (`88e37c1`), same local JDK, same classpath file; analyzer rebuilt after the
schema `0.2` changes. Node and edge counts moved for four reasons: overloads are now separate
symbols, duplicate relationships at one source position are stored once, supertypes and constructor
calls now resolve to canonical type ids instead of external placeholders, and primitive types are no
longer emitted as dependencies.

| Measurement | Schema 0.1 | Schema 0.2 |
| --- | ---: | ---: |
| Nodes, syntax-only | 1,268 | 1,140 |
| Edges, syntax-only | 2,424 | 2,656 |
| Resolved call/type edges, syntax-only | 22 | 724 |
| Explicitly unresolved, syntax-only | 1,491 | 1,018 |
| Resolution rate, syntax-only | 1.45% | 41.56% |
| Nodes, with classpath | 867 | 943 |
| Edges, with classpath | 2,580 | 2,585 |
| `JDT_BINDING` edges, with classpath | 1,652 | 1,668 |
| Explicitly unresolved, with classpath | 17 | 3 |
| Resolution rate, with classpath | 98.98% | 99.82% |

The syntax-only jump is the significant one: without any classpath the analyzer previously emitted
every supertype and constructor as an unresolved placeholder, even when JDT could already prove the
binding from the source batch and the JDK. That was unresolved-by-omission, not by evidence.

Framework facts are unchanged, which is the intended result: 17 endpoints, 6 controllers, 6 entities,
6 tables, 9 transaction boundaries. `CREATES` is new at 88 edges.

Wall time on this run was 2.4 s syntax-only and 4.2 s with the classpath, single-run and including
JVM startup. It is recorded for reference, not compared against the 0.1 figures, which were measured
under different machine conditions.

### Impact and plugin, schema 0.2

`impact VetRepository --depth 3` with the classpath: 13 direct and 3 transitive impacts, every edge
at confidence 1.00, reaching `VetController.findPaginated`, `showResourcesVetList`, `showVetList`,
and both `GET /vets` and `GET /vets.html`. The context packet is unchanged at 13 callers,
2 endpoints, 1 dependency, and 20 evidence edges.

The Maven plugin run against the same checkout:

| Plugin measurement | Schema 0.1 | Schema 0.2 |
| --- | ---: | ---: |
| Production nodes | 380 | 400 |
| Production edges | 753 | 777 |
| `JDT_BINDING` edges | 328 | 368 |
| Explicitly unresolved edges | 16 | 0 |
| `VetRepository` direct impacts | 2 | 3 |
| `VetRepository` transitive impacts | 3 | 3 |

The production-only graph now has no unresolved edges at all, and `impact-report.sarif` parses as
SARIF 2.1.0 with one location per impacted node.

## Schema 0.3 re-baseline: full pipeline

Same commit (`88e37c1`), same classpath file, analyzer rebuilt after field access, exception flow,
overrides, dispatch normalization, Spring bean wiring, and the architecture layer were added.

| Measurement | Schema 0.2 | Schema 0.3 |
| --- | ---: | ---: |
| Nodes, with classpath | 943 | 995 |
| Edges, with classpath | 2,585 | 3,330 |
| `JDT_BINDING` edges | 1,668 | 2,154 |
| Explicitly unresolved edges | 3 | 3 |
| Wall time, with classpath | 4.2 s | 3.0 s |

New relationship kinds on this repository: 389 `READS`, 27 `WRITES`, 51 `THROWS`, 3 `CATCHES`,
10 `OVERRIDES`, 4 dispatch-normalized `CALLS`. New summary nodes: 17 workflows, 11 modules,
3 business capabilities, 1 configuration property, 1 external service.

A defect surfaced while writing the grounded question set: JPA's `@Table` names its table with the
`name` member, and the analyzer had only ever read `value`. Every table in every earlier benchmark
was therefore named after its entity class (`table:Owner`) rather than after the schema
(`table:owners`), and was silently marked `inferred=true`. Fixed; Petclinic now reports `owners`,
`pets`, `vets`, and `visits` correctly.

Framework counts are otherwise unchanged: 17 endpoints, 6 controllers, 6 entities, 6 tables,
9 transaction boundaries.

### Grounded evaluation

Ten questions over this repository, keyed to source names and file:line rather than graph ids:

| Metric | Result |
| --- | ---: |
| Questions passed | 10 / 10 |
| Structural accuracy | 1.000 |
| Evidence recall | 1.000 |
| Groundedness | 1.000 |
| Median latency per question | 3 ms |

Two of those questions failed on the first run and both were real gaps rather than bad
expectations: the `@Table` defect above, and a context packet with no forward view, which made
"what does this endpoint expose" unanswerable. Both are fixed and both are now covered by tests.

## Reproducibility

The cloned source and generated graph are local benchmark artifacts under `work/benchmarks` and are intentionally not committed to the product repository. Re-run this benchmark against the pinned commit before and after each semantic-resolution change.

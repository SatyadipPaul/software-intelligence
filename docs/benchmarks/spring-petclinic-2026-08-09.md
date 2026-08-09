# Spring Petclinic benchmark

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
| Indexing and JSON export | 2.92 seconds |
| Graph nodes | 834 |
| Graph edges | 2,402 |
| `JDT_BINDING` edges | 1,474 |
| `INTRA_REPOSITORY_SYMBOL` edges | 0 |
| Explicitly unresolved edges | 17 |
| Resolution rate | 98.86% |

The extra 1.84 seconds is the cost of compiler-grade bindings and is the appropriate tradeoff for high-confidence impact analysis.

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
| Plugin analysis wall time | 6.02 seconds |
| Production nodes | 356 |
| Production edges | 636 |
| `JDT_BINDING` edges | 211 |
| Explicitly unresolved edges | 16 |
| `VetRepository` direct impacts | 2 |
| `VetRepository` transitive impacts | 3 |

The direct production callers were `VetController.findPaginated` and `VetController.showResourcesVetList`; the transitive impacts included `showVetList`, `GET /vets`, and `GET /vets.html`.

## Reproducibility

The cloned source and generated graph are local benchmark artifacts under `work/benchmarks` and are intentionally not committed to the product repository. Re-run this benchmark against the pinned commit before and after each semantic-resolution change.

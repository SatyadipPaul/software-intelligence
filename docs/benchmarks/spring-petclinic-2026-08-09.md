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
| Indexing and JSON export | 1.08 seconds |
| Graph nodes | 1,268 |
| Graph edges | 2,424 |
| Resolved in-repository call edges | 22 |
| Explicitly unresolved call/type edges | 1,491 |
| Resolution rate over resolved/unresolved edges | 1.45% |
| HTTP endpoints detected | 17 |
| Controllers detected | 6 |
| JPA entities detected | 6 |
| Database tables inferred | 6 |
| Transaction boundaries detected | 9 |

The repository's own first Maven compile on the same local JDK took 84.24 seconds, including dependency resolution. Our source indexing completed in about 1.3% of that elapsed time. This is an operational comparison, not a claim that the analyzer replaces compilation.

## Verified impact examples

`VetRepository` produced four direct callers, including `VetController.findPaginated`, with source ranges and resolver confidence 0.98. `VetController` produced two exposed routes. The analyzer also composes class-level mappings, for example:

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

The highest-value gap is now clear: Petclinic's interface/repository and framework wiring create many calls that require classpath-aware symbol resolution. The current 1.45% resolution rate is a measurement, not something to hide. The next milestone is Maven/Gradle classpath-aware JDT bindings, followed by interface dispatch and Spring dependency-injection resolution.

## Reproducibility

The cloned source and generated graph are local benchmark artifacts under `work/benchmarks` and are intentionally not committed to the product repository. Re-run this benchmark against the pinned commit before and after each semantic-resolution change.


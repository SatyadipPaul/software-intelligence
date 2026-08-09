# Spring Petclinic REST benchmark

Run date: 2026-08-09  
Repository: `spring-petclinic/spring-petclinic-rest`  
Repository commit: `698bd83`

## Build-aware method

This application generates REST interfaces from `openapi.yml`. The benchmark therefore ran Maven `generate-sources` first, then generated the dependency classpath, then ran the local analyzer. No Docker or application startup was used.

```powershell
mvn generate-sources
mvn dependency:build-classpath -Dmdep.outputFile=target/repo-intel.classpath -Dmdep.includeScope=test
java -jar repo-intel.jar inspect . --classpath (Get-Content -Raw target/repo-intel.classpath)
```

## Results

| Measurement | Result |
| --- | ---: |
| Java files after generation | 137 |
| Generated Java files | 28 |
| Analyzer wall time | 4.17 seconds |
| Graph nodes | 2,121 |
| Graph edges | 8,315 |
| `JDT_BINDING` edges | 5,068 |
| Explicitly unresolved edges | 71 |
| Resolution rate | 98.62% |
| Controllers | 10 |
| Services | 2 |
| Repository components | 21 |
| Entities | 8 |
| REST endpoints | 38 |
| `DEPENDS_ON` edges | 874 |

The source-only run detected only 8 endpoints because the OpenAPI-generated interfaces did not exist until Maven's `generate-sources` phase. The build-aware run recovered the complete endpoint surface, including `POST /owners`, `GET /owners/{ownerId}`, and `DELETE /pets/{petId}`.

## Packaged plugin validation

The installed Maven plugin was then run directly against the same checkout. It produced `target/repo-intel/repo-graph.json`, `impact-report.txt`, and `impact-report.sarif` without Docker or a running service.

| Plugin measurement | Result |
| --- | ---: |
| Plugin graph nodes | 1,728 |
| Plugin graph edges | 5,434 |
| Plugin `JDT_BINDING` edges | 2,756 |
| Plugin REST endpoints | 38 |
| `ClinicService` direct impact | 43 callers |
| `ClinicService` transitive impact | 0 additional nodes at depth 3 |

The impact report selected the real source interface (`type:org.springframework.samples.petclinic.service.ClinicService`) rather than a provisional external placeholder and listed controller methods with file/line evidence.

## Finding

For build systems with generated source, repository intelligence must run after source generation and use the build's resolved classpath. The Maven plugin is the correct integration point because it can obtain both automatically; a raw file scanner cannot reliably infer generated API contracts.

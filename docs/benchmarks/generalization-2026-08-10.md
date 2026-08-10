# Generalization benchmark: does this work outside Spring Petclinic?

Run date: 2026-08-10

Every benchmark before this one used Spring Petclinic or Spring Petclinic REST. Those are both
small Spring MVC applications with nearly identical shape, so passing on both proved far less than
it appeared to. This run analyzes two repositories chosen specifically for being *unlike* them.

| | jackson-databind | junit5 |
| --- | --- | --- |
| Commit | `2b72ff3` | `de6d621` |
| Build | Maven, single module | **Gradle Kotlin DSL, multi-module** |
| Framework | **none** | **none** |
| Java files | 1,366 | 1,738 |
| Character | extreme generics, deep inheritance | 27 source roots, heavy annotations/SPI |

## Results, syntax-only (no classpath supplied)

| Measurement | jackson-databind | junit5 |
| --- | ---: | ---: |
| Analysis wall time | 20 s | 23 s |
| Graph nodes | 45,595 | 33,206 |
| Graph edges | 282,759 | 188,861 |
| Resolution rate | 86.2% | 87.3% |
| Modules detected | 83 (= its 83 packages) | 27 (= its Gradle source roots) |
| Deterministic across runs | yes | yes |

Determinism holds at 45k nodes and 283k edges, which is the first time that invariant has been
tested at a scale where an ordering bug could hide.

The derived layers correctly produce **nothing** here: zero endpoints, zero workflows, zero
capabilities, zero tables. A non-Spring library has no HTTP or messaging entry points, and the
framework layer inventing some would have been the worst possible outcome. The `architecture`
command now says so explicitly rather than printing an empty section that reads like a failure.

Centrality found what a Jackson maintainer would name: `JavaType`, `DeserializationContext`,
`ObjectMapper`, `JsonNode`, `SerializationContext` — in that order, from PageRank over 6,631 types,
with no framework knowledge involved.

## Four defects this exposed

Every one of these was invisible on Petclinic and would have shipped.

**1. Module detection produced 908 modules for 83 packages.** `packageGroup` trimmed the last
segment of a qualified name, so a nested type `perf.MediaItem.Content` made `perf.MediaItem` look
like a package. 824 of the 908 "modules" were actually classes. Petclinic has few nested types, so
its 11-modules-for-7-packages looked plausible enough to pass unnoticed. The package now comes from
the declaring file's package declaration, with a convention-based fallback for graphs that carry no
package edges.

**2. Retrieval was dominated by English question words.** BM25 scored `on`, `does`, and `what` as
search terms, so *"what does ObjectMapper depend on?"* returned a test constant named `FAIL_ON` as
its top hit. Question words are now removed from queries — never from the index, since a symbol
genuinely named `on` must still be findable.

**3. Retrieval ranked test fields above the type asked about.** After the stopword fix, the top five
results were all fields named `objectMapper` in unrelated test classes. A kind prior now ranks
operational entities and types above methods, and methods above fields. `ObjectMapper` is now the
top hit.

**4. Context compression discarded the relevant evidence.** On a hub symbol with 8,598 incoming
references, the packet holds thousands of equally-confident edges; compression sorted by confidence
alone and truncated from the end, leaving an arbitrary survivor. `ask` answered a question about
`ObjectMapper` by citing a relationship between two unrelated `perf` classes — a technically valid
claim and a useless answer. Compression now ranks edges that touch the subject first.

## One usability defect

`architecture` printed each module's full coupling map inline. At three modules that is informative;
at 83 it produced single lines thousands of characters wide. It now shows the largest modules with
their three heaviest coupling targets and a count of the rest.

## Scale limit found and acted on

Rendering jackson-databind at the old default of 1,200 nodes cost **12 seconds of layout** before
the page could paint, because force-directed repulsion is O(N²) per iteration. The default is now
500 nodes and the iteration count scales down as N rises, holding the product near a fixed budget:
the same graph now lays out in about 2 seconds. Anything larger belongs in `--format GRAPHML` and a
dedicated tool, which the CLI states on stderr rather than silently producing something unusable.

## What this run did not test

Honest boundaries, since the point of this document is to stop overclaiming from a narrow sample:

- **Neither repository was analyzed with a resolved classpath.** Both were run syntax-only, so the
  86–87% resolution figures are not comparable to Petclinic's 99.8% classpath-aware number.
- **No grounded question set exists for either repository.** The corpus is still 18 questions across
  the two Spring repositories. These runs prove the analyzer does not fall over and that its derived
  layers stay silent when they should; they do not measure answer quality here.
- **Gradle classpath discovery was not exercised**, because junit5 was not built first.
- Still untested: Kotlin or Scala sources in a mixed repository, a repository large enough to exceed
  memory, and generated-source builds outside the Maven `generate-sources` case already covered.

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

## Classpath-aware run and a grounded question set

jackson-databind was then built for its classpath and re-analyzed:

| Measurement | syntax-only | with classpath |
| --- | ---: | ---: |
| Resolution rate | 86.2% | **99.74%** |
| Explicitly unresolved edges | 39,506 | 488 |

A ten-question grounded set for jackson-databind now ships in
`evaluation/jackson-databind.questions.tsv` and passes 10/10 with structural accuracy, evidence
recall, and groundedness all at 1.000, median 91 ms per question. It is the first set for a
repository with no framework at all: no endpoints, tables, or guards to lean on, so the questions
exercise inheritance, calls, module structure, and exception flow instead.

Ground truth was read from the source rather than from tool output. That distinction is the whole
point of the exercise - a question set generated from what the tool already says is a change
detector, not a correctness test, and would pass forever while the answers were wrong together.
Writing it that way is what found the next two defects.

## Two more defects, found by writing the question set

**5. Dispatch normalization produced 83,271 edges - a quarter of the graph.** `ValueDeserializer.deserialize`
has 166 in-repository implementations and generated 13,924 edges by itself; `java.lang.Object.toString`
generated 8,092. "This call may reach any of 166 places" is not an answer anyone can act on, and at
that volume it buries the edges that are. Dispatch is no longer normalized through `java.lang.Object`
methods, and above 12 candidates the fan-out is recorded as an `implementations` count on the
declared method instead of as edges. Dispatch edges fell to 31,116 with resolution unchanged at
99.74%, and the 57 heavily polymorphic methods keep their counts.

**6. Every declaration's provenance pointed into its Javadoc.** JDT's `getStartPosition()` includes
the doc comment, so `ObjectMapper` - declared on line 94 - was recorded at line 36, a line of prose.
Every consumer inherited this: SARIF locations for CI code scanning, the viewer's "declared" field,
enrichment work packets, and any IDE jump. Declarations now carry the position of their *name*, so
the four types checked (`ObjectMapper` 94, `BaseJsonNode` 30, `JavaType` 19, `TypeFactory` 65) match
what `grep` reports exactly. The Spring question sets were updated accordingly: their endpoint
expectations moved from the `@GetMapping` line to the method line, which is the line a reviewer
would cite.

A third, smaller gap: "which module contains this symbol?" was unanswerable, because module
membership is an incoming `CONTAINS` edge and context packets only followed outgoing ones. Context
now includes it.

## What this run did not test

Honest boundaries, since the point of this document is to stop overclaiming from a narrow sample:

- **junit5 was not analyzed with a resolved classpath**, so its 87.3% is syntax-only and not
  comparable to jackson's 99.74%. Gradle classpath discovery is therefore still unexercised.
- **No question set exists for junit5.** The corpus is 28 questions across three repositories, still
  far from the 200+ the roadmap calls for, and junit5 remains a crash-and-silence test only.
- **The jackson set is ten questions written by one author in one sitting.** It covers inheritance,
  impact, module structure, and lookup; it does not cover generics resolution, annotation
  processing, or the serializer/deserializer registry indirection that is the hard part of this
  codebase.
- Still untested: Kotlin or Scala sources in a mixed repository, a repository large enough to exceed
  memory, and generated-source builds outside the Maven `generate-sources` case already covered.

# Does a build classpath change what retrieval finds?

Date: 2026-09-16. JDK 25, single run, jackson-databind `2b72ff3` and junit5 `de6d621` — the two
repositories [the retrieval baseline](retrieval-2026-09-09.md) had to run without one.

That baseline carried a caveat in its first paragraph: *no build classpath was supplied, so
resolution is lower than the analyzer's best*. The caveat was honest but untested. It implied a
better graph was available and that the numbers would move once it was. This run supplies the
classpath and measures both halves of that claim. The first half holds. The second does not.

## Getting a classpath at all

Both repositories were previously blocked for environmental reasons, and both turned out to be
reachable.

**jackson-databind** builds against `tools.jackson:jackson-base:3.3.0-SNAPSHOT`, whose only
published home is the Sonatype snapshot repository — denied here by network policy, and no amount of
retrying changes that. But the parent is not the dependency; it is the POM that *names* the
dependencies. Its source is on GitHub, which is reachable, so the chain was built from source
instead: `jackson-bom` and its `base` module installed non-recursively, which is enough to resolve
the child. The two snapshot dependencies it then names were substituted with their nearest Central
releases:

| Declared | Resolved as |
| --- | --- |
| `tools.jackson.core:jackson-core:3.3.0-SNAPSHOT` | `3.2.2` |
| `com.fasterxml.jackson.core:jackson-annotations:2.23-SNAPSHOT` | `2.22` |

Both are one minor version behind what the build asks for. That is a real approximation and it is
recorded here rather than buried: these are the stable streaming and annotation APIs, and the
binding resolution measured below does not depend on anything that moved between those versions.

**junit5** needed no substitution — only a correct init script. The earlier attempt failed after
three minutes, which read like a slow build dying; it was not. Gradle configures this build in 17
seconds. The failure was `allprojects`, which **Isolated Projects** rejects outright, and which
cannot be escaped with `--no-configuration-cache` because Gradle refuses to disable the
configuration cache while Isolated Projects is on. `gradle.lifecycle.beforeProject` works, and 21
modules wrote a classpath file each — the shape the shipped advice already recommends.

Discovery merged them without changes: `classpath: 77 entries from 21 per-module classpath files`.
The per-module merge added for junit5 in August is the reason nothing here needed new code.

## What the classpath bought

| Repository | Measurement | syntax-only | with classpath |
| --- | --- | ---: | ---: |
| jackson-databind | Resolution rate | 77.01% | **99.18%** |
| jackson-databind | Unresolved edges | 27,755 | **777** |
| jackson-databind | Nodes / edges | 45,595 / 261,559 | 43,798 / 275,641 |
| junit5 | Resolution rate | 78.78% | **98.82%** |
| junit5 | Unresolved edges | 19,394 | **870** |
| junit5 | Nodes / edges | 33,382 / 175,076 | 29,647 / 179,068 |

Node counts *fall* while edge counts rise, which is the shape a correct classpath run should have:
placeholder nodes standing in for unresolvable types collapse into the real types they were guessing
at, and the edges that could not be attributed before now land somewhere.

## What it did not buy

Every retrieval metric, on both repositories, in all three modes:

| Repository | Mode | anchor recall | recall@1 | MRR | median ms (syntax → classpath) |
| --- | --- | ---: | ---: | ---: | --- |
| jackson-databind | BM25 | 0.769 → 0.769 | 0.769 → 0.769 | 0.769 → 0.769 | 78 → 81 |
| jackson-databind | TREE | 0.769 → 0.769 | 0.769 → 0.769 | 0.769 → 0.769 | 61 → 63 |
| jackson-databind | HYBRID | 0.769 → 0.769 | 0.769 → 0.769 | 0.769 → 0.769 | 62 → 66 |
| junit5 | BM25 | 0.636 → 0.636 | 0.636 → 0.636 | 0.636 → 0.636 | 50 → 50 |
| junit5 | TREE | 0.727 → 0.727 | 0.636 → 0.636 | 0.667 → 0.667 | 36 → 39 |
| junit5 | HYBRID | 0.727 → 0.727 | 0.636 → 0.636 | 0.682 → 0.682 | 37 → 40 |

Not one figure moved. The same six questions fail before and after, with the same wrong anchors in
the same order. The traversal harness likewise stays at 13/13 and 11/11, structural accuracy,
evidence recall and groundedness all 1.000 in both runs.

The one thing that does change is answer size: jackson-databind's median answer grows from 5,363 to
6,132 symbols, +14%. The extra resolved edges are real and they reach real symbols. They simply do
not reach the symbols these questions are missing.

## Reading it

**The caveat in the retrieval baseline was unnecessary, and now it is known to be.** Resolution and
retrieval are close to independent on this ground truth. Retrieval ranks names and card text; a
classpath changes which *edges* exist between already-named nodes. A question that fails because the
asker said "an explicit JSON null" and the type is called `NullNode` fails identically at 77%
resolution and at 99%.

**This is the third measurement in a row to come back negative**, after branch summaries and the
blind re-run of that same experiment. The pattern across all three is the same: the interventions
that sound like they should help retrieval — richer graphs, better prose, more resolved edges — all
address something other than the actual failure, which is that the asker's words and the code's
identifiers are different words. Nothing that leaves the identifiers alone has moved a number yet.

**What it does buy is everything downstream of anchoring.** 777 unresolved edges instead of 27,755
is the difference between an impact query that can be trusted and one that cannot, and the +14%
answer size is evidence the extra edges carry real symbols. That is worth having. It is just not a
retrieval improvement, and the roadmap should stop implying it is one.

## Reproduce

```bash
# jackson-databind: build the parent chain from source, then resolve with released substitutes
git clone https://github.com/FasterXML/jackson-bom.git
(cd jackson-bom && mvn -N install && cd base && mvn -N install)
(cd jackson-databind && mvn dependency:build-classpath \
    -Dmdep.outputFile=target/repo-intel.classpath -Dmdep.includeScope=test \
    -Djackson.version.core=3.2.2 -Djackson.version.annotations=2.22)

# junit5: one task per project, never allprojects
(cd junit5 && ./gradlew -I dump-cp.gradle repoIntelClasspath)

repo-intel evaluate <repo> evaluation/<repo>.questions.tsv \
  --retrieval-only BM25,TREE,HYBRID --fail-under 0 --discover-classpath
```

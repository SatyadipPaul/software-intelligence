# What the descent is actually worth: benchmarking the chooser

Date: 2026-09-16. JDK 25, the 200-question corpus, build classpaths supplied, `TREE` mode
throughout — descent alone, no flat hits behind it, so what is measured is the descent.

Two things prompted this. LARGER (arXiv 2605.16352) charges graph retrieval with fragmenting an
agent's interaction loop through separate traversal stages, which is a fair description of
`navigate`. And a gap found while discussing the design's rating: **`modules/evaluation` had never
referenced `Chooser` at all**, so every retrieval number this repository has published came from
`TreeNavigator.DETERMINISTIC` — BM25 over card text. The descent had only ever been scored with a
scorer that cannot bridge a vocabulary gap, and the architecture's ceiling was unknown.

## The measurement

Three choosers over identical trees, questions and anchoring rules. Only the branch decision differs.

| Repository | Chooser | anchor recall | recall@1 | MRR |
| --- | --- | ---: | ---: | ---: |
| junit5 | deterministic (BM25) | 0.273 | 0.182 | 0.211 |
| junit5 | dense (encoder) | 0.200 | 0.145 | 0.168 |
| junit5 | **oracle** | **0.982** | **0.873** | **0.920** |
| jackson-databind | deterministic | 0.300 | 0.214 | 0.248 |
| jackson-databind | dense | 0.243 | 0.157 | 0.198 |
| jackson-databind | **oracle** | **0.971** | **0.957** | **0.961** |
| spring-petclinic | deterministic | 0.560 | 0.440 | 0.486 |
| spring-petclinic | dense | 0.540 | 0.380 | 0.441 |
| spring-petclinic | **oracle** | **0.980** | **0.800** | **0.879** |

The oracle knows each question's answer and always descends towards it. It is an instrument, not a
mode: it separates two failures every previous number had conflated. When a descent misses, either
the chooser picked the wrong branch, or the answer was **not reachable by descending at all** —
wrong tree shape, too narrow a beam, too shallow a cap, an anchoring rule that stopped early. An
oracle cannot make the first mistake, so whatever it still misses is the second.

## The finding

**The tree is almost never the limitation. The chooser is the whole of it.**

On all three repositories the answer is reachable by descent about 97–98% of the time, and the best
available automatic chooser reaches 27–56% of it. On jackson-databind that is 0.300 against a
ceiling of 0.971 — the descent is delivering **under a third** of what its own structure permits.

That reverses how this project has been reading its own numbers. Every earlier conclusion about
tree navigation — that it ties flat retrieval, that it is a cost optimisation with an explainability
bonus, that the exit criterion was not met — was a measurement of one scorer, reported as a
measurement of an architecture. The architecture has roughly three times more in it than has ever
been extracted.

## Why the dense chooser is worse, and what it cost to find out

A first version embedded each branch card on its own and scored **0.036** on junit5 — an eighth of
the lexical chooser. That was not a property of dense choosing; it was a bug in the comparison. A
branch card is close to contentless: a module is one word, a package is a dotted path. The lexical
scorer never had this problem because `CardIndex` scores a node as its **whole subtree**.

Fixing it meant representing a branch by the mean of the vectors of every card beneath it — which is
aggregation of *representations*, the technique
[the subtree-dilution run](subtree-dilution-2026-09-16.md) identified after aggregation of *scores*
came out worse than either of its inputs. That lifted it from 0.036 to 0.200. Still below lexical,
and the lesson is the same one twice: on a tree, **what a branch contains beats what its card says**,
whichever signal is doing the reading.

## A real assistant, on eight questions

The point of `navigate` is a chooser that reasons rather than scores. It had never been run against
the corpus. Eight subject-free questions the deterministic chooser **misses entirely** were
navigated by hand through the file exchange, choosing branches from the cards alone:

| Question | Target | Steps | Result |
| --- | --- | ---: | --- |
| ju-013 aborts a test rather than failing it | `Assumptions` | 6 | exact |
| ju-017 publish extra output to the report | `TestReporter` | 6 | exact |
| ju-023 fails a test that runs too long | `Timeout` | 5 | exact |
| ju-024 switches a test off without deleting it | `Disabled` | 5 | exact |
| ju-043 what a build tool calls to run tests | `Launcher` | 6 | exact |
| ju-050 wraps a computation that may have failed | `Try` | 5 | exact |
| jd-012 stands for an explicit JSON null | `NullNode` | 5 | exact |
| jd-022 incoming value does not fit the target shape | `MismatchedInputException` | 5 | exact |

**8 of 8, five or six steps each.**

**This sample is contaminated and proves less than it appears to.** The same agent wrote these
questions and navigated them, so it knew the answers before reading a single card — exactly the
failure the blind Javadoc experiment exists to prevent. It does not establish a rate, and it must
not be quoted as one.

What it does establish is narrower and still worth having: the cards carry enough to navigate by,
the id-rejection contract holds over a real multi-step session, and a reasoning chooser can reach
targets the lexical one misses entirely. The oracle is the uncontaminated result; this is a
demonstration that the path the oracle proves exists is walkable by something other than an oracle.

## LARGER's charge

Conceded in part, and answered in part.

**Conceded:** a descent costs one round trip per level — five or six here — where a lexical-anchor
plus one structural expansion costs none. For an agent loop that is a real cost, and
`DENSE_HYBRID`'s single-shot shape currently scores better end to end (0.522 subject-free) than any
descent measured here.

**Answered:** the cost is not buying nothing. It is buying access to a ceiling of 0.97 that no
single-shot mode has demonstrated, and the gap between 0.30 and 0.97 is where a reasoning chooser
lives. The charge is that traversal fragments the loop; the reply is that it fragments the loop *in
exchange for* the only headroom measured anywhere in this project.

## What follows

1. **The chooser is where the value is, not the index.** Effort spent on tree shape, card text or
   enrichment is spent against a limit that is already at 0.97.
2. **A blind assistant benchmark is the missing measurement.** Someone other than the question
   author has to navigate, or an agent has to do it without the answer in context. Until then the
   real ceiling sits somewhere between the lexical 0.30 and the oracle 0.97, and nobody knows where.
3. **Subtree aggregation of representations is now implemented** (`DenseTreeIndex`) and is the piece
   task #15 was blocked on.

## Reproduce

```bash
repo-intel evaluate <repo> evaluation/<repo>.questions.tsv \
  --retrieval-only TREE --fail-under 0 --discover-classpath \
  --chooser DETERMINISTIC|DENSE|ORACLE
```

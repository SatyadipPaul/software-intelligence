# Tier 0 contextual cards: the first intervention that moved a number

Date: 2026-09-16. JDK 25, single run, the 200-question corpus, build classpaths supplied.

Four interventions had come back negative — branch summaries, their blind re-run, build classpaths,
and the tree itself at scale. All four enriched the structure *around* identifiers while leaving
identifiers the only vocabulary retrieval could match. This one adds vocabulary, deterministically,
with no model and no network.

## What changed

The analyzer now records the **first sentence of a declaration's Javadoc** as a `doc` attribute on
types and methods. That is the whole change. Camel-case splitting already existed; annotation
literals (`annotation.KafkaListener.topics: payment-authorized`) were already ingested. Javadoc was
the one piece of human-language text in a Java repository that the graph was throwing away.

Only the first sentence, capped at 240 characters. A full Javadoc is mostly `@param` and `@return`
lines whose terms recur on every card in the repository — that is term-index noise, raising every
document's length without distinguishing any of them.

## The result

Subject-free questions — the 161 of 200 that describe what code does rather than naming it:

| Repository | before | after (BM25) | after (HYBRID) |
| --- | ---: | ---: | ---: |
| sample-commerce | 0.529 | 0.647 | **0.706** |
| spring-petclinic | 0.368 | 0.368 | **0.421** |
| jackson-databind | 0.083 | **0.217** | 0.200 |
| junit5 | 0.022 | **0.239** | 0.152 |
| **all 200** | **0.180** | **0.304** | 0.292 |

Questions that name their subject stay where they were, 0.974 → 1.000 under HYBRID. Nothing was
traded away.

Headline metrics, before → after:

| Repository | Mode | anchor recall | MRR |
| --- | --- | --- | --- |
| sample-commerce | BM25 | 0.720 → 0.760 | 0.531 → 0.560 |
| sample-commerce | HYBRID | 0.680 → **0.800** | 0.553 → **0.647** |
| spring-petclinic | BM25 | 0.480 → 0.520 | 0.369 → 0.378 |
| spring-petclinic | HYBRID | 0.520 → **0.560** | 0.441 → **0.486** |
| jackson-databind | BM25 | 0.214 → **0.329** | 0.203 → **0.266** |
| jackson-databind | HYBRID | 0.214 → 0.314 | 0.200 → 0.250 |
| junit5 | BM25 | 0.182 → **0.345** | 0.156 → **0.257** |
| junit5 | HYBRID | 0.164 → 0.291 | 0.155 → 0.227 |

junit5's subject-free rate went from 1 question in 46 to 11. That is the largest single movement
this project has measured.

## On contamination

The summary experiment's lesson was that the author of the enrichment must not be the author of the
questions. Here the `doc` text is **upstream-authored** for three of the four repositories: the
Javadoc in spring-petclinic, jackson-databind and junit5 was written by their maintainers, years
before these questions existed. Those three are clean measurements.

The fixture is not. Its Javadoc was written in this repository, by the same author as its questions
— `BillingLedger`'s doc comment reads "Depends on two other packages on purpose". Its numbers should
be read as the contaminated ceiling, not as evidence. The two large repositories, which moved most,
are the trustworthy half.

A weaker caveat applies to the questions themselves: they were written by reading declarations —
`grep` over class, interface and annotation lines — rather than Javadoc, but that is a description
of the process, not a guarantee like the blind experiment had.

## The finding that cuts against the tree

**Tier 0 helps flat retrieval more than it helps the descent**, and on the two large repositories it
has put BM25 clearly ahead:

| | BM25 | HYBRID |
| --- | ---: | ---: |
| jackson-databind, subject-free | **0.217** | 0.200 |
| junit5, subject-free | **0.239** | 0.152 |

The cause is `CardIndex` scoring a node as its **whole subtree**. That aggregation was the right fix
for an earlier defect — cards were unsearchable for their contents, and jackson's TREE score went
from 0.400 to 1.000 when it was added. But it dilutes exactly the signal Tier 0 adds: one type's doc
sentence is a hundredth of a hundred-type package's text, where flat retrieval gets that sentence as
a document of its own.

So the better the card text gets, the more the subtree aggregation costs. That is a real design
tension and it is not fixed here — fixing it casually would risk the defect the aggregation exists
to prevent. It is recorded as the next thing to measure.

## Reading it

**The vocabulary-gap diagnosis holds, and is now supported rather than merely inferred.** The one
intervention that added human-language vocabulary moved the number by 69% relative; the four that
added structure moved nothing. That is the clearest signal this corpus has produced about where
effort belongs.

**It also bounds what a generative Tier 1 is for.** Free upstream prose took subject-free retrieval
from 0.180 to 0.304. The remaining 0.696 is where a written description would have to earn its cost
— and much of it is in repositories whose Javadoc is missing or boilerplate, which is precisely the
enterprise profile the corpus does not yet contain.

**CI gate holds**: fixture MRR, TREE and HYBRID 0.647 against BM25 0.560. Traversal stays 200/200
with groundedness 1.000.

## Reproduce

```bash
repo-intel evaluate <repo> evaluation/<repo>.questions.tsv \
  --retrieval-only BM25,TREE,HYBRID --fail-under 0 --discover-classpath
```

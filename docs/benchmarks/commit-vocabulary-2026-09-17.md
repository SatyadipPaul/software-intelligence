# Commit messages as Tier 0 prose, and the pooling bug they exposed

Date: 2026-09-17. JDK 25, build classpaths supplied, `potion-base-32M` int8 as the encoder.
**175 questions over three repositories**, not the full 200: the `sample-commerce` fixture lives
inside this repository and has no history of its own, so its 25 questions are excluded from every
row here. All baselines below are re-measured on the same 175.

This closes task #12, the last Tier 0 source. It is the first addition since Javadoc that **helps** —
but only after a change that had nothing to do with commit messages.

## What was built

`CommitVocabulary`, a deterministic analyzer pass run after parsing.

1. **Focused commits only.** The filter that does the work is not a list of bot patterns but
   **breadth**: a commit touching more than ten files is a sweep — a copyright year, a formatter, a
   licence header — and says nothing about any one of them. spring-petclinic's widest commit touches
   1082 files; its median touches one. With no limit, `PetValidator` is described as "Updated
   Copyright to year 2025; Apply spring-format plugin; Fix Apache license headers". At ten, it is
   described as "chaining validation so we can see multiple error messages".
2. **Merges excluded**, because a merge subject describes an integration and its file list is the
   union of everything merged — the widest sweep in the log.
3. **The newest twenty per file**, which is where recency enters: an older subject describes code
   that may have been rewritten twice since.
4. **The same bounded digest as test names** — leading verb dropped, ticket numbers and URLs
   stripped, phrases chosen greedily by new vocabulary per character, 240-character cap. Both
   sources now share `VocabularyDigest`, so they cannot drift into disagreeing about what a digest is.
5. **Only where there is nothing better**, applying what task #13 measured rather than re-learning it.

### The guard, and what it decided

The task flagged that this is the first text the analyzer reads that is **not the source at the
pinned commit**. Three things were decided rather than discovered:

- **It lives in the graph, under a key that names its provenance.** The attribute is `history`, so
  any card showing it says where the claim came from, and the repository node records
  `history.commit`, `history.focusedCommits`, `history.describedSymbols` and
  `history.sweepThreshold` — a graph carrying history says *which* history.
- **A shallow clone is refused, not tolerated.** Two shallow clones of one commit can hold different
  history, which would make this the only part of the graph that is not a function of the commit.
- **It is off by default**, behind `--commit-vocabulary`, and needs `git` on the PATH.

## The first measurement, which said no

| Configuration | BM25 | DENSE_HYBRID |
| --- | ---: | ---: |
| off | **0.271** | **0.493** |
| commit vocabulary on | 0.250 | 0.472 |

Per repository under `DENSE_HYBRID`, the directions disagreed — spring-petclinic 0.632 → 0.553,
junit5 0.391 → **0.435**, jackson-databind 0.483 → 0.450 — which is what a wash looks like when it is
made of two opposite effects rather than of nothing.

Looking at which questions moved found both effects, and one of them was familiar:

- **junit5 gained, consistently.** Ten questions moved and nearly all moved up; `TestDescriptor` and
  `Launcher` entered the top five.
- **The other two lost by displacement.** `OwnerController` answered pc-048 at rank 1 and fell out of
  the top five. `AnnotatedClass` answered jd-064 at rank 1 and fell out of the top five — **the same
  node, the same question, the same failure** that [the test-vocabulary run](test-vocabulary-2026-09-17.md)
  recorded a day earlier from a completely different source of prose.

## The actual bug: one source, one vote

Twice is a mechanism, not a coincidence. `DenseIndex` embedded each node as one concatenated string —
name, then doc sentence, then whatever else had been added — and mean-pooled it. Mean-pooling weighs
a source by **how many words it happens to have**, and the added sources are the wordy ones. A name
is three words. A commit digest is forty. So a change log outvotes the identifier, on exactly the
nodes where the identifier was already the right answer.

The fix needs no fitted constant, which is why it is allowed here: embed each source separately,
normalise each to a unit vector, and average them. Every source counts once, whatever its length.
A node with one source is left bit-for-bit as it was — which is every node until an optional prose
pass is switched on — so the shipped default is untouched.

| Configuration (DENSE_HYBRID) | subject-free |
| --- | ---: |
| off | 0.493 |
| off, with per-source pooling in place (control) | **0.493** |
| commit vocabulary, concatenated | 0.472 |
| commit vocabulary, **one vote per source** | **0.507** |
| test vocabulary, one vote per source | 0.486 |
| both sources, one vote per source | 0.500 |

The control reproduces the baseline exactly, per repository as well as in total, which is what makes
the rest of the column readable.

## The result

**Commit vocabulary, with per-source pooling, is the first Tier 0 addition since Javadoc that pays.**

| Repository | anchor recall | MRR | subject-free |
| --- | --- | --- | --- |
| spring-petclinic | 0.720 → **0.740** | 0.602 → 0.594 | 0.632 → **0.658** |
| junit5 | 0.491 → **0.509** | 0.335 → **0.345** | 0.391 → **0.413** |
| jackson-databind | 0.557 → 0.557 | 0.431 → 0.423 | 0.483 → 0.483 |
| **all 144 subject-free** | | | **0.493 → 0.507** |

Coverage, which is part of why the gain is modest:

| Repository | focused commits | production types | of which described |
| --- | ---: | ---: | ---: |
| spring-petclinic | 866 | 25 | 6 |
| junit5 | 10211 | 1189 | 395 |
| jackson-databind | 6958 | 856 | 145 |

Three further things the table says.

**Test vocabulary is still negative, and now that is not a pooling artefact.** Under per-source
pooling it recovers from 0.479 to 0.486 on these three repositories and stays below the 0.493
baseline. #13's conclusion survives its own best defence: a test name describes a scenario, and the
questions ask about things.

**Running both sources is worse than running the better one.** 0.500 against 0.507, because test
vocabulary claims a node first and blocks the better prose from reaching it.

**BM25 cannot do this.** Its number stays negative (0.271 → 0.250) and per-source pooling is not
available to it: a term index has one document per node and no way to say that forty words from one
source should count as much as three from another, only length normalisation applied after the fact.
That is a real, structural advantage of the dense path, and the first one measured here.

## What follows

1. **`--commit-vocabulary` is opt-in because of the guard, not because it hurts.** It needs a full
   clone and a `git` binary; a default cannot assume either. Its measured effect is positive.
2. **"Does it help on average" is not enough of a test.** Both prose experiments were net-negative
   for a reason that only showed up per question, and per-source pooling was invisible until two
   independent sources broke the same node the same way. Anything that writes prose onto a node —
   Tier 1 included — needs the displacement check.
3. **Tier 0 is finished.** Javadoc 0.180 → 0.304, the encoder → 0.404, test-code demotion → 0.522 on
   the full corpus; commit messages add the last deterministic prose a repository contains. What is
   left is generated text, which is #16, and the enterprise corpus nobody has.

## Reproduce

```bash
repo-intel evaluate <repo> evaluation/<repo>.questions.tsv \
  --retrieval-only DENSE_HYBRID --fail-under 0 --discover-classpath \
  --embedding-model <model-dir> [--commit-vocabulary] [--test-vocabulary]
```

The subject-free split is every question whose text does not contain its subject's simple name:
144 of the 175 here, 161 of 200 on the full corpus.

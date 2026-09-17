# Test method names as Tier 0 prose: a negative result, and a sharper reason than expected

Date: 2026-09-17. JDK 25, the 200-question corpus, build classpaths supplied, `potion-base-32M`
int8 as the encoder. Two retrieval modes, three configurations, same graphs otherwise.

This closes task #13, which argued that test names are the cheapest Tier 0 source and the one most
likely to survive on undocumented enterprise code: `shouldRejectPaymentWhenBalanceIsInsufficient` is
a sentence with the spaces removed, business code is often uncommented but almost always tested, and
the graph already held those names with nothing associating them with their subject.

The association was built. It costs recall.

## What was built

A deterministic analyzer pass, `TestVocabulary`, run after parsing and before every other layer.

1. **A test method** is a method declared under a test source root that either carries a JUnit-family
   test annotation or is named the JUnit 3 way. Setup and teardown are excluded: they are named for
   their mechanics, and `set up` is not vocabulary.
2. **Its subjects** are the production types it reaches through `CALLS`, external symbols excluded.
   A test calls whatever it needs, so only the three least-exercised are kept — the type a test
   touches that almost nothing else touches is the type that test is about.
3. **A fixture is not a subject.** A type more than half the suite calls is what the suite is built
   on; jackson-databind's `ObjectMapper` is called by 52% of its tests, and three thousand unrelated
   behaviours squeezed into one card describe the library, not the type.
4. **The digest** is the selected names split into words, with leading `should`/`test`/`verify`
   dropped and issue numbers stripped, chosen greedily by how many words each phrase adds that have
   not been said yet, to a 240-character budget — the same cap as the Javadoc first sentence, for
   the same reason.
5. **It lands on the production type**, never on the test's own node.

## The measurement

Subject-free questions — the 161 of 200 that describe what code does rather than naming it, the
same split as [the Tier 0 run](tier0-context-2026-09-16.md):

| Configuration | BM25 | DENSE_HYBRID |
| --- | ---: | ---: |
| off (shipped before this task) | **0.311** | **0.522** |
| on, only where the type has no Javadoc | 0.304 | 0.509 |
| on, alongside whatever Javadoc exists | 0.292 | 0.484 |

Headline metrics, off → gap-filling → alongside:

| Repository | Mode | anchor recall | MRR |
| --- | --- | --- | --- |
| sample-commerce | BM25 | 0.760 → 0.760 → 0.760 | 0.560 → 0.560 → 0.560 |
| spring-petclinic | BM25 | 0.520 → 0.520 → 0.520 | 0.378 → 0.378 → 0.386 |
| junit5 | BM25 | **0.345** → 0.327 → 0.309 | **0.255** → 0.251 → 0.233 |
| jackson-databind | BM25 | **0.343** → 0.343 → 0.329 | **0.269** → 0.269 → 0.250 |
| sample-commerce | DENSE_HYBRID | 0.840 → 0.840 → 0.840 | 0.655 → 0.655 → 0.655 |
| spring-petclinic | DENSE_HYBRID | **0.720** → 0.720 → 0.700 | 0.602 → 0.603 → **0.619** |
| junit5 | DENSE_HYBRID | 0.491 → 0.491 → 0.473 | 0.335 → **0.348** → 0.291 |
| jackson-databind | DENSE_HYBRID | **0.557** → 0.529 → 0.500 | **0.431** → 0.413 → 0.409 |

The `off` column reproduces the published 0.311 and 0.522 exactly, so the three configurations differ
in this pass and nothing else.

## The finding

**A test name describes a scenario. A question asks about a thing. Those are different registers,
and swapping one for the other is not an improvement.**

The hypothesis behind #13 was that test names close the register gap because they are written in a
reader's words. They are — but they answer *what happens when*, and every question in this corpus
asks *what is the thing that*. The distinction is invisible until you look at a case it breaks.

jackson-databind, gap-filling configuration, question jd-064 — "What holds the reflected view of one
class and its annotations?", answer `AnnotatedClass`. Before: **rank 1**. After: **not in the top
five**. `AnnotatedClass` is one of the seventeen jackson types that got a digest, and the digest
reads:

> get super types for child class; find aliases with json alias; get wrapper name null introspector;
> annotated field equality; array type introspection

Every phrase is true of tests that exercise `AnnotatedClass`. Not one of them says that it *holds a
reflected view of a class*. Two hundred and forty characters of accurate, irrelevant text were added
to the one document that already answered the question, and the document stopped answering it. The
same thing happened to jd-059 and `LogicalType`.

That is the mechanism, and it explains the shape of the whole table: the pass is harmless where it
adds nothing (sample-commerce and spring-petclinic, 1 type described between them), and it costs
most where it describes the most. It is not that the digest was noisy. It is that a correct
description of a type's tests is a poor description of the type.

Seventeen descriptions were enough to lose two answers on jackson-databind.

## Coverage, which is also part of the answer

| Repository | production types | with Javadoc | given a digest |
| --- | ---: | ---: | ---: |
| spring-petclinic | 25 | 18 | 1 |
| junit5 | 1189 | 652 | 156 |
| jackson-databind | 856 | 606 | 17 |

The gap-filling rule only writes where there is no Javadoc, and most of the undocumented types are
also the ones no test reaches specifically. The idea is not cheap to apply; it is cheap to *build*
and thin in effect, which is a different thing.

## What this does not establish

Every repository here is documented open-source code. The case #13 was built for — business logic
with no comments and a large test suite — is not represented in this corpus at all, and cannot be,
until an enterprise-shaped repository is available to measure against. So this rules the pass out
for documented code and says nothing about the rest.

That is why it is kept rather than deleted: `--test-vocabulary` runs it, it is off everywhere by
default including `Layers.all()`, and the negative result is reproducible by anyone who doubts it.

One more thing survives. The gap-filling configuration improved junit5's **MRR** (0.335 → 0.348)
while leaving its recall untouched, by moving five answers up within the top five. The vocabulary is
not worthless; it is worth less than what it displaces.

## What follows

1. **Tier 0's remaining source is commit messages (#12), and this result changes what to expect of
   it.** A commit message says *why a thing exists*, which is closer to *what it is* than a test
   name is. But the same failure is available to it, and the same experiment will catch it: measure
   gap-filling before measuring anything else.
2. **Adding text to a document that already answers is a risk, not a free action.** Anything that
   writes prose onto a node — Tier 1 included — needs the displacement test this run stumbled into,
   not just a "does it help on average" number.
3. **The enterprise profile stays unmeasured.** Three of the repository's open conclusions now
   depend on a corpus nobody has: this one, the Tier 1 contamination caveat, and the register-gap
   generalisation.

## Reproduce

```bash
repo-intel evaluate <repo> evaluation/<repo>.questions.tsv \
  --retrieval-only BM25 --fail-under 0 --discover-classpath \
  --embedding-model <model-dir> [--test-vocabulary]
```

The subject-free split is every question whose text does not contain its subject's simple name,
which is 161 of the 200.

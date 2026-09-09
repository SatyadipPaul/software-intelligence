# Retrieval baseline: flat BM25 against tree navigation

Date: 2026-09-09. JDK 25, single run, four repositories at the commits their question sets pin:
the fixture, spring-petclinic `88e37c1`, jackson-databind `2b72ff3`, junit5 `de6d621`. No build
classpath was supplied, so resolution is lower than the analyzer's best — but identically so for
every retrieval mode, which is what the comparison needs.

Reproduce with:

```bash
repo-intel evaluate <repo> evaluation/<repo>.questions.tsv \
  --retrieval-only BM25,TREE,HYBRID --fail-under 0
```

## What is being measured, and what is not

The existing harness resolves each question's declared subject by name and then scores the
traversal. That measures what the graph does with a *perfect anchor* — it skips retrieval entirely,
which is why no number in this repository described retrieval before this one.

`--retrieval-only` asks the same questions in words, supplies no subject, and scores only whether
the ranked anchors reached the symbol the question is about. Everything after anchoring is identical
in all three modes, so the difference between them is retrieval and nothing else.

An anchor counts as reaching the subject when it is the subject, a member of it, the type owning it,
or something the graph records as *containing* it. That last case is not generosity: "which module
contains ObjectMapper" is answered by a module, and the question sets say so in their expected
names. Scoring it as a miss at first made a correct answer look like a navigator defect.

**precision@k is not reported.** The roadmap asked for it, and it is the wrong metric for this
ground truth: most questions declare one relevant symbol, so precision@k cannot exceed 1/k and would
measure that cap rather than the ranking. Recall@k and MRR are reported instead.

**Anchor coverage** is reported for the questions whose declared answer names more than one symbol:
the share of that answer the anchor set itself reaches, scored before any traversal. Every other
metric asks whether retrieval found *the* symbol, which is all a single-subject question can ask.
This is the only measurement that says anything about anchoring on a set.

## Result

| Repository | Mode | anchor recall | recall@1 | MRR | median ms |
| --- | --- | --- | --- | --- | --- |
| sample-commerce | BM25 | 0.900 | 0.800 | 0.833 | 1 |
| sample-commerce | TREE | **1.000** | **0.900** | **0.950** | 2 |
| sample-commerce | HYBRID | **1.000** | **0.900** | **0.950** | 1 |
| spring-petclinic | BM25 | 1.000 | 1.000 | 1.000 | 2 |
| spring-petclinic | TREE | 1.000 | 1.000 | 1.000 | 3 |
| spring-petclinic | HYBRID | 1.000 | 1.000 | 1.000 | 1 |
| jackson-databind | BM25 | 1.000 | 1.000 | 1.000 | 104 |
| jackson-databind | TREE | 1.000 | 1.000 | 1.000 | **63** |
| jackson-databind | HYBRID | 1.000 | 1.000 | 1.000 | 77 |
| junit5 | BM25 | 0.875 | 0.875 | 0.875 | 169 |
| junit5 | TREE | **1.000** | 0.875 | **0.917** | **39** |
| junit5 | HYBRID | **1.000** | 0.875 | **0.938** | 52 |

Where the modes disagree, they disagree in opposite directions — which is the case for the union:

| Question | BM25 | TREE | HYBRID |
| --- | --- | --- | --- |
| sc-005 Which controller exposes the payment authorization route? | – | 2 | 2 |
| sc-007 Which type consumes the payment event topic? | 3 | 1 | 1 |
| ju-002 Which module contains the Test annotation? | 1 | 3 | 1 |
| ju-003 What is affected by a change to the Extension interface? | – | 1 | 2 |

Every other question is rank 1 in all three modes.

## Reading it

**HYBRID is never worse than flat retrieval, and better on two of four corpora.** It wins the
fixture (MRR 0.950 against 0.833) and junit5 (0.938 against 0.875), and ties on Petclinic and
jackson-databind, where flat retrieval already scores 1.000 and there is nothing left to win.
**`--retrieval` therefore defaults to `HYBRID`.**

**TREE alone now also matches or beats flat retrieval everywhere**, after the name-collision defect
below was fixed: on junit5 it went from missing ju-002 outright to anchor recall 1.000 and MRR 0.917,
against flat retrieval's 0.875. It stays behind `HYBRID` on rank — ju-002 lands third rather than
first — which is the wrong-branch commitment the design predicted, and exactly what the flat hits
behind a descent are there to cover.

**At scale the descent is cheaper than the ranking.** On jackson-databind flat BM25 takes 273 ms per
question because it scores all 45,595 graph nodes; the descent takes 101 ms because it reads 18
cards. On junit5, 56 ms against 153 ms. The crossover is real and in the direction the design
argued: ranking pays for the whole repository per question, navigating pays for one path.

**The corpora that mattered were the ones not yet run.** The fixture had said "no measurable
difference"; three real repositories changed that conclusion in both directions, and found three
defects that ten fixture questions never could.

## What running the real corpora found

Each of these was a genuine defect, caught because the corpus was large enough to expose it.

**1. A branch card could not be searched for what it contains.** A card prints a handful of
exemplars, so on jackson-databind the term `ObjectMapper` appeared on none of the 83 module cards —
including the module that contains it. The descent was choosing between branches that all scored
zero: **TREE measured 0.400 against flat retrieval's 1.000**, a regression on the repository the
design expected it to win. A node is now scored as the document formed by its whole subtree, with
BM25's length normalization doing the discriminating. Subtrees are contiguous ranges in a preorder
numbering, so this costs two binary searches rather than a materialized index per level.

**2. Term frequency is the wrong signal for "is it in here".** Even with subtree scoring, the
package `node` — which mentions "json" and "node" hundreds of times and does not contain `JsonNode`
— outscored the package that does. A branch containing something named exactly what was asked for
is now ranked ahead of every branch that merely repeats its words, as a separate signal rather than
a weight. That took jackson-databind from 0.500 to 1.000.

**3. A question word was being read as the subject.** `subjectOf` takes the longest capitalized
token, and a question starts with one: "Which table does the Owner entity persist to?" yielded
`Which`, not `Owner`. Flat retrieval tolerated it by matching the question's other words; tree
navigation did not, because it steers by the branch holding the subject and no branch holds a symbol
called Which. **This dropped Petclinic from 1.000 to 0.800** before it was found. It is a
pre-existing defect in shared code that the tree merely exposed, and fixing it helps both modes.

**4. A method named like the subject stood in for the type.** Exact-name matching indexed every node
in one list, so "the Test annotation" matched junit5's **408 methods called `test()`** as readily as
its 2 types called `Test`. The signal was 99.5% noise, "holds the subject" was true almost
everywhere, and the descent fell back to term frequency — which in a testing framework sends it to
whichever module says "test" most often. Declarations and members are now indexed separately and a
name resolves against declarations first, falling back to members only when no declaration anywhere
carries it, so a question about `PaymentService.authorize` still reaches its method. This is what
took `TREE` on junit5 from a miss to anchor recall 1.000, and it is why `HYBRID` is now the default.

A fifth item was a measurement defect rather than a code one: the harness scored an anchor on the
module *containing* the subject as a miss, which marked several correct answers wrong. See the
scoring rule above.

## Anchoring on a set

Coverage is scored on anchors alone, deliberately. Traversal from one good anchor reaches the rest —
that is what the graph is for, and the traversal harness already scores 12/12 on Petclinic with one
anchor per question. Counting it here would measure the graph a second time instead of retrieval.

Petclinic, `HYBRID`, with the Maven classpath, over the 5 questions whose answer names several
symbols:

| | anchors 1 | anchors 5 | anchors 10 | anchors 12, beam 8 | anchors 12, beam 16 |
| --- | --- | --- | --- | --- | --- |
| anchor coverage | 0.000 | 0.500 | 0.561 | **0.714** | 0.714 |
| cards read | 12.3 | 12.3 | 12.3 | 24.1 | 41.3 |

Two things follow. Widening the beam past 8 buys nothing but cards — 41.3 against 24.1 for the same
0.714 — so `--beam 8` is where a plural question should sit and the default of 4 stays right for the
single-anchor case. And a single anchor covers **none** of a plural answer, which is not a failure of
the tool but a statement of what one anchor can be: the subject, not the answer.

The separation between modes on this metric is the widest anywhere in this benchmark:

| Petclinic, anchors 10 | BM25 | TREE | HYBRID |
| --- | --- | --- | --- |
| anchor coverage | **0.000** | 0.561 | 0.561 |

Flat retrieval scores zero because its ten anchors for "Which routes does OwnerController expose?"
are ten spellings of `OwnerController` — the type, its constructor, six of its methods, and its test
class — and not one endpoint. The descent returns the controller and three of the seven routes. Both
modes score 1.000 on every single-subject metric for this question; only coverage separates them,
and it separates them completely.

## Does the build classpath change the conclusion?

Everything above was run with no classpath, which the analyzer resolves far less from. Petclinic was
re-run with its real Maven classpath (170 entries) to check whether richer resolution moves the
balance:

| Petclinic, anchors 10 | anchor recall | recall@1 | MRR | coverage |
| --- | --- | --- | --- | --- |
| no classpath, BM25 | 1.000 | 1.000 | 1.000 | 0.000 |
| no classpath, HYBRID | 1.000 | 1.000 | 1.000 | 0.490 |
| with classpath, BM25 | 1.000 | 1.000 | 1.000 | 0.000 |
| with classpath, HYBRID | 1.000 | 1.000 | 1.000 | **0.561** |

The comparison between modes is unchanged; coverage improves, because more resolved edges mean more
of the answer exists to be anchored on. The traversal harness moves from 7/12 to **12/12** with the
classpath, and its groundedness from 0.993 to 1.000 — those failures were the missing classpath, not
the questions.

jackson-databind and junit5 could not be re-run this way here. jackson-databind is a
`3.3.0-SNAPSHOT` whose parent POM lives in the Sonatype snapshots repository, which this
environment's proxy refuses with a 403; junit5's Gradle build fails before producing the per-module
classpath files its question set documents. Both remain measured at no-classpath resolution.

## The finding that matters most: retrieval only works when the question names the symbol

Thirteen questions were added across the four corpora that deliberately do **not** name their
subject — phrased the way someone who does not yet know the class's name would ask. Every corpus
falls, and on the two large ones every single new question is missed by every mode:

| Repository | questions | BM25 MRR | TREE MRR | HYBRID MRR |
| --- | --- | --- | --- | --- |
| spring-petclinic | 10 → 19 | 1.000 → 0.664 | 1.000 → 0.655 | 1.000 → 0.655 |
| jackson-databind | 10 → 13 | 1.000 → 0.769 | 1.000 → 0.769 | 1.000 → 0.769 |
| junit5 | 8 → 11 | 0.875 → 0.636 | 0.917 → 0.667 | 0.938 → **0.682** |

0 of 6 new questions answered on jackson-databind and junit5; 2 of 7 on Petclinic. The old questions
still pass, so nothing regressed — the new ones simply ask something the retrieval layer cannot do.

**What that means for everything above.** Retrieval here, flat and tree alike, is close to an
exact-name matcher with tie-breaking. Every 1.000 in the first table was a question saying "what
breaks if `JavaType` changes", and the exact-name signal settles those before a single card is read.
The descent's advantages are real and hold on the harder set — `HYBRID` still leads junit5, 0.682
against 0.636 — but they are advantages in *ranking what a name already found*, not yet in finding
something whose name the asker does not know.

It also gives branch summaries their purpose. They are the one mechanism that can answer a
subject-free question, because they put words in the tree that the code does not contain, and on
Petclinic they recovered two of the seven. That is the experiment worth repeating on jackson and
junit5, where the modules are named `util`, `impl` and `misc` and there is the most to gain.

## A harder question set, and what it exposed

Every question in the corpus named its subject — "what breaks if `VetRepository` changes" — which
the exact-name signal settles before any card text is read. That is why Petclinic scored 1.000 on
every ranking metric, and it flattered retrieval badly.

Seven questions were added that do **not** name their subject, phrased the way someone who does not
yet know the symbol would ask: "Which page does the application serve at its root?", "Where are the
specialties a veterinarian holds stored?", "What checks a pet is valid before the form is accepted?"
All 19 pass the traversal harness with the Maven classpath. Retrieval collapses:

| Petclinic, 19 questions | anchor recall | recall@1 | MRR | coverage |
| --- | --- | --- | --- | --- |
| BM25 | 0.737 | 0.632 | **0.664** | 0.000 |
| TREE | 0.737 | 0.632 | 0.655 | **0.625** |
| HYBRID | 0.737 | 0.632 | 0.655 | **0.625** |

From 1.000 to roughly 0.66. Five of the seven new questions are missed by every mode. The earlier
perfect scores measured the question style, not the retrieval — and on this harder set `TREE` is
marginally *behind* flat retrieval on MRR, by one question's rank, while still holding the whole of
the coverage advantage.

## Do branch summaries improve descent?

On the easy questions, no. On the hard ones, yes — with a caveat about who wrote what that has to
come first.

The loop ran as documented, with no product change and no credentials: `enrich-targets --branches`
wrote packets, those were answered with `SUMMARY` claims, and `enrich-apply` verified every one
against the graph. 12 claims applied, 0 rejected, 0 disputed.

| Petclinic, HYBRID | anchor recall | recall@1 | MRR |
| --- | --- | --- | --- |
| 10 original questions | 1.000 → 1.000 | 1.000 → 1.000 | 1.000 → 1.000 |
| 19 questions incl. subject-free | 0.737 → **0.842** | 0.632 → 0.632 | 0.655 → **0.672** |

Two questions are recovered by the summaries alone, and the mechanism is exactly what the design
claimed: a summary supplies domain vocabulary the code does not contain. "Which table stores the
clinic's **clients**?" is answerable only because a summary calls `Owner` "a client of the clinic" —
**the word "client" appears nowhere in Petclinic's Java source.** The same holds for "clinic
**staff**" and the `vet` module.

**The caveat, which limits what this shows.** The summaries were written before these questions
existed, so they were not tuned to them. The questions were *not* written blind to the summaries.
Someone reaching for a phrase to describe an owner and someone reaching for a phrase to summarise
the owner package will land on the same domain words, and that similarity is doing some of the work
here. So this establishes the **mechanism** — a summary can carry vocabulary the code lacks, and
retrieval will use it — and **overstates the effect size**. Questions written by someone who has not
seen the summaries would settle it, and nothing here should be quoted as the size of the win.

## Cost and shape

| | fixture | this repo | spring-petclinic | jackson-databind | junit5 |
| --- | --- | --- | --- | --- | --- |
| graph nodes | 72 | 2,405 | 1,171 | 45,595 | 33,206 |
| tree entries | 28 | 1,028 | 268 | 26,530 | 18,841 |
| depth | 3 | 4 | 3 | 5 | 7 |
| capabilities | 2 | 2 | 3 | 0 | 0 |
| grouped sibling sets | 0 | 18 | 0 | 1,015 | 814 |
| cards read per question | 11.8 | — | 12.6 | 18.2 | 17.4 |

Two shapes worth noting. **jackson-databind and junit5 have no capabilities at all**, so the tree
degrades to its structural axis and says so in `index` output — the no-framework path the design
promised, exercised. And **cards read per question grows barely at all** — 11.8 on a 72-node graph,
18.2 on a 45,595-node one — because depth grows logarithmically while flat ranking grows linearly.
That is the property the whole approach rests on, and it holds.

Derivation is below measurement noise on the large repositories: on jackson-databind, `inspect`
(analysis only) took 33.8 s and `index` (the same analysis plus derivation) took 33.3 s — the run
with the extra work was the faster one, so run-to-run variance dominates whatever the derivation
costs. On this repository, where runs are short enough to compare, it is about 80 ms on a 2.8 s
analysis. The card index is built once per tree rather than once per question; rebuilding it per
question was most of an earlier 1,002 ms median on jackson-databind.

## What would change the conclusion

**Questions written by someone who did not write the summaries.** That is the one measurement here
whose method is compromised, and it is cheap to fix.

**More questions that do not name their subject.** Seven of them took Petclinic from 1.000 to 0.66
and are the only reason any of the harder findings above exist. Every other repository in the corpus
is still scored entirely on questions that name what they are looking for, so their 1.000s should be
read as "not yet asked anything difficult" rather than as a ceiling reached.

**A question no single anchor can answer.** Every plural question in the corpus is still reachable by
traversal from one subject, because the architecture layer's capability and module nodes give the
graph a place to hang a plural answer from. A question spanning two disconnected regions — "compare
what the vet and owner subsystems persist" — would be the first that genuinely *requires* the anchor
set, and none exists yet.

**Classpaths for jackson-databind and junit5,** which this environment cannot produce. Petclinic
shows resolution changes coverage but not the mode comparison; whether that holds on the two large
repositories is unverified.

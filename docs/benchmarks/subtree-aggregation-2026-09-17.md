# Aggregating representations: three ways to fold a subtree into one vector

Date: 2026-09-17. JDK 25, the 200-question corpus, build classpaths supplied, `TREE` mode
throughout, `potion-base-32M` int8 as the encoder. Only the branch decision differs between rows.

This closes task #15. [The subtree-dilution run](subtree-dilution-2026-09-16.md) established the
problem — `CardIndex` scores a node as its whole subtree, so one good card in a large branch is
averaged away — and recorded that the obvious repair, `max(pooled, bestCard)`, came out *worse than
either of its inputs* on jackson-databind. PARADE (arXiv 2008.09093) explains why: that max is taken
over **scores**, and aggregating passage **representations** is the more effective move. So the
question here is not whether aggregation helps, it is which aggregation.

## What was built

`DenseTreeIndex` now folds the tree in a single post-order pass: a node's vector is its own card
combined with its children's already-folded vectors, so the whole tree costs one traversal rather
than a range scan per query. Three aggregators share that pass.

| Aggregator | A branch is… | Weight per child |
| --- | --- | --- |
| `MEAN` | the average of every card beneath it | the number of cards it stands for |
| `MAX` | the strongest value any card below shows on each dimension | n/a, element-wise maximum |
| `CENTROID` | the average of its children's *directions* | one, whatever its size |

**Sum is not a fourth row.** Cosine ignores magnitude, so a sum and a mean produce identical
rankings; measuring both would have been theatre. PARADE's remaining aggregators (Attn, Transformer)
are query-dependent, which puts them back on the score side of the line this task was told not to
cross, and would cost a pass over the subtree per query rather than per tree.

`CENTROID` is not from PARADE. It is the direct test of the dilution hypothesis itself: if the
problem is that a large branch drowns a small one, giving every child one vote should fix it.

## The measurement

| Repository | Chooser | anchor recall | recall@1 | MRR |
| --- | --- | ---: | ---: | ---: |
| junit5 | deterministic (BM25) | 0.273 | 0.182 | 0.211 |
| junit5 | dense `MEAN` | **0.218** | 0.145 | 0.173 |
| junit5 | dense `MAX` | 0.018 | 0.018 | 0.018 |
| junit5 | dense `CENTROID` | 0.073 | 0.055 | 0.059 |
| junit5 | oracle | 0.982 | 0.873 | 0.920 |
| jackson-databind | deterministic | 0.300 | 0.214 | 0.245 |
| jackson-databind | dense `MEAN` | **0.229** | 0.129 | 0.171 |
| jackson-databind | dense `MAX` | 0.043 | 0.014 | 0.023 |
| jackson-databind | dense `CENTROID` | 0.157 | 0.100 | 0.121 |
| jackson-databind | oracle | 0.971 | 0.957 | 0.961 |
| spring-petclinic | deterministic | 0.560 | 0.440 | 0.486 |
| spring-petclinic | dense `MEAN` | 0.540 | 0.380 | 0.441 |
| spring-petclinic | dense `MAX` | 0.320 | 0.240 | 0.274 |
| spring-petclinic | dense `CENTROID` | **0.600** | 0.380 | 0.464 |
| spring-petclinic | oracle | 0.980 | 0.800 | 0.879 |

Deterministic and oracle rows do not touch `DenseTreeIndex`; the deterministic rows were re-run
today on all three repositories and reproduced the previous numbers, so they are a control rather
than a quotation. The oracle rows are quoted from
[the chooser-ceiling run](chooser-ceiling-2026-09-16.md).

## The finding

**`MEAN` wins, and both alternatives are worse. The dilution hypothesis, as stated, is wrong.**

Two negative results, and they are the useful part.

**`MAX` collapses.** 0.018 on junit5 is one question in fifty-five — worse than picking branches by
coin flip would be. The reason is visible in the unit test: taking the element-wise maximum over
hundreds of unit vectors takes the positive extreme on *every* dimension independently, so the
result resembles no card that actually exists, and after normalising it is nearly the same vector
for every large branch. The saturation is worst exactly where the decision matters most — at the top
of the tree, where a branch stands for the most cards and one wrong turn loses everything below it.
PARADE's Max worked over a few dozen passages of one document. A module here stands for thousands.

**`CENTROID` splits by tree shape, and that split is the real answer.** It is the best chooser
measured on spring-petclinic — 0.600, above both `MEAN` (0.540) and the lexical chooser (0.560),
which no dense variant had managed before. On junit5 it falls to 0.073, a third of `MEAN`. The
difference between those repositories is depth: spring-petclinic is one shallow module where equal
votes are roughly true, while junit5 and jackson-databind are deep, and there a unit-weighted
centroid lets a package holding one class outvote an entire subsystem. Removing size-weighting does
not remove dilution; it trades dilution for the opposite distortion, and on a real tree the opposite
distortion is worse.

So the honest reading of #15 is that **subtree aggregation was already doing the right thing**, and
the dense chooser's weakness relative to BM25 is not the aggregator. It is somewhere else: the
encoder's fit to identifier-shaped text, the card text itself, or the fact that the lexical scorer
gets exact-name matches for free. #18's conclusion stands unchanged — the ceiling is 0.97 and the
chooser reaches a third of it — and this run rules out one explanation for the gap rather than
closing it.

## A drift worth recording

The rewrite changed junit5's `MEAN` number from 0.200 to 0.218 and jackson-databind's from 0.243 to
0.229 — one question each way. The old implementation summed over a node's contiguous preorder
range; the new one folds through `IndexTree.children`. Those differ for a node with two parents, and
jackson-databind's tree has exactly ten of them (record components that are both a field and an
accessor). The range form counted such a node once, in whichever parent's span it fell into; the
fold counts it under both parents, which is what the tree actually says. The new behaviour is the
correct one and the drift is the cost of saying so.

## What follows

1. **`MEAN` stays the default.** `MAX` and `CENTROID` are kept behind `--chooser` because a negative
   result nobody can re-run is not a result.
2. **`CENTROID` on shallow trees is an unclaimed 0.04.** One repository is not a finding, and
   shape-dependent defaults are how a library becomes unpredictable. If a fourth and fifth shallow
   repository agree, a depth-conditioned default becomes arguable; until then it is a flag.
3. **The dense-versus-lexical gap needs a different hypothesis.** The next candidate is the card
   text rather than the fold — which is what tasks #12 and #13 are about.

## Reproduce

```bash
repo-intel evaluate <repo> evaluation/<repo>.questions.tsv \
  --retrieval-only TREE --fail-under 0 --discover-classpath \
  --embedding-model <model-dir> \
  --chooser DETERMINISTIC|DENSE|DENSE_MAX|DENSE_CENTROID|ORACLE
```

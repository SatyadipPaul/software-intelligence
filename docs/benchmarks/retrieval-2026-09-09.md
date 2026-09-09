# Retrieval baseline: flat BM25 against tree navigation

Date: 2026-09-09. JDK 25, single run, `fixtures/sample-commerce` (72 graph nodes, 28 tree entries,
depth 3) and this repository (2,389 graph nodes, 1,016 tree entries, depth 4).

Reproduce with:

```bash
repo-intel evaluate fixtures/sample-commerce evaluation/sample-commerce.questions.tsv \
  --retrieval-only BM25,TREE,HYBRID --fail-under 0
```

## What is being measured, and what is not

The existing harness resolves each question's declared subject by name and then scores the
traversal. That measures what the graph does with a *perfect anchor* — it skips retrieval entirely,
which is why no number in this repository has ever described retrieval before.

`--retrieval-only` asks the same questions in words, supplies no subject, and scores only whether
the ranked anchors reached the symbol the question is about. Everything after anchoring is identical
in all three modes, so the difference between them is retrieval and nothing else.

Landing on a member of the right type counts as reaching it, and so does landing on the type of the
right member: both put the traversal in the right place.

**precision@k is not reported.** The roadmap asked for it, and it is the wrong metric for this
ground truth: each question declares one relevant symbol, so precision@k cannot exceed 1/k and would
measure that cap rather than the ranking. Recall@k and MRR are reported instead.

## Result on the fixture

| Metric | BM25 (flat) | TREE | HYBRID |
| --- | --- | --- | --- |
| anchor recall | 0.900 | 0.900 | 0.900 |
| recall@1 | 0.800 | 0.800 | **0.900** |
| recall@5 | 0.900 | 0.900 | 0.900 |
| mean reciprocal rank | 0.833 | 0.833 | **0.900** |
| median latency | 1 ms | 4 ms | 2 ms |
| cards read per question | — | 12.0 | 12.0 |

Per question, where the modes disagree:

| Question | BM25 | TREE | HYBRID |
| --- | --- | --- | --- |
| sc-002 Which HTTP endpoint does PaymentService ultimately serve? | 1 | 3 | 1 |
| sc-007 Which type consumes the payment event topic? | 3 | 1 | 1 |
| sc-005 Which controller exposes the payment authorization route? | – | – | – |

Every other question is rank 1 in all three modes.

## Reading it honestly

**The union is the result; the tree alone is not.** TREE ties BM25 in aggregate — it wins sc-007 and
loses sc-002, netting zero. HYBRID takes both, which is the whole of its advantage here. That is the
predicted behaviour (each mode covers the other's failure), but on ten questions it rests on a
single question changing rank, so the honest summary is *one question, on a corpus too small to
separate them*.

**sc-005 fails in every mode**, so it says nothing about retrieval. The question asks for a
controller while its declared subject is the endpoint; every mode returns the controller. That is a
question-set property, not a ranking failure.

**The fixture cannot carry this comparison.** Ten questions over ten types is not where a table of
contents earns anything — a tree helps when there is too much to rank flatly, and here there is not.
The corpora that would separate the modes (Petclinic, jackson-databind, junit5) have grounded
question sets but were not available in the environment this was measured in, so the honest state is
that tree retrieval is **measured as not-a-regression, and not yet measured as a win**.

## A worked case at a larger scale

Not a benchmark — one question, no ground truth — but it shows the mechanism the aggregate is too
small to expose. Asked of this repository:

```
repo-intel ask . "which module contains the BM25 retrieval index?" --retrieval BM25|TREE --anchors 3
```

| Mode | Top anchor |
| --- | --- |
| BM25 | `module:modules/index-tree` — wrong |
| TREE | `module:modules/query-engine` — right |

Flat retrieval matches the word "index" against a module *named* index-tree. The descent reads the
root's card, sees that query-engine's card is where the retrieval vocabulary actually lives, and
stops at module level because the question is structural — 5 cards read, no model involved.

## Cost

| | fixture | this repository |
| --- | --- | --- |
| graph nodes | 72 | 2,389 |
| tree entries | 28 | 1,016 |
| depth | 3 | 4 |
| grouped sibling sets | 0 | 18 |
| derivation | under 1 s | 2.8 s wall, inside a 2.8 s analysis |
| pinned tree file | 15 KB | 548 KB |

Derivation is a single pass over the graph plus one SHA-256 over the canonical export, so it scales
with the graph rather than with the question, and is done once per graph rather than per query.

## What would change the conclusion

Run the same command against Petclinic, jackson-databind, and junit5. jackson-databind is the
interesting one: no framework at all, so the capability axis is empty and the tree is the structural
axis alone — the shape most likely to show whether a derived table of contents beats flat ranking
when the repository is large and its names repeat.

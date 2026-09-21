# Subtree dilution: a real bias whose obvious repair is worse

Date: 2026-09-16. JDK 25, the 200-question corpus, build classpaths supplied.

[Tier 0 contextual cards](tier0-context-2026-09-16.md) improved retrieval everywhere and left a
tension behind. The gain favoured **flat** retrieval, which scores each type as its own document,
over the **descent**, which scores a node as its whole subtree. On junit5's subject-free questions
that came out 0.239 flat against 0.152 hybrid, where before Tier 0 the two modes were within one
question of each other.

The diagnosis was mechanical and looked solid: pooling term frequency over a subtree is an
*average*, so one type whose doc sentence answers the question exactly is a hundredth of a
hundred-type package's text. A package full of weak mentions outranks the package that holds the
answer. The better the card text gets, the more the pooling costs.

That diagnosis is correct. The repair it suggests is not.

## What was tried

Max-passage scoring — the standard remedy for exactly this bias in document retrieval. A node is
credited with the better of its pooled subtree score and its best single card scored on its own
text:

```java
return Math.max(pooled, bestCard(queryTerms, start, end));
```

`bestCard` walks the same postings the pooled score uses, so only cards carrying a query term are
visited, and sibling ranges are disjoint — ranking one card's children stays one pass over the
postings inside the parent. Cost was not the problem.

## The result

| Repository | Mode | pooled (before) | max-passage | change |
| --- | --- | ---: | ---: | --- |
| jackson-databind | TREE anchor recall | 0.300 | 0.271 | **worse** |
| jackson-databind | TREE MRR | 0.248 | 0.211 | **worse** |
| junit5 | TREE anchor recall | 0.273 | 0.255 | **worse** |
| junit5 | HYBRID anchor recall | 0.291 | 0.255 | **worse** |
| spring-petclinic | TREE anchor recall | 0.560 | 0.580 | better |
| spring-petclinic | TREE MRR | 0.486 | 0.476 | worse |
| sample-commerce | TREE anchor coverage | 0.250 | 0.179 | **worse** |

Per question on the two large repositories: 6 lost, 2 gained. Every loss was a question previously
ranked 2–5 that fell out of the top five altogether — `jd-024` "which exception is raised when a
constructor refuses the values it was handed" went from rank 2 to absent.

## Why it failed, measured rather than reasoned

Scoring by `bestCard` **alone** is the diagnostic that settles it:

| Repository | pooled only | best-card only | max of the two |
| --- | ---: | ---: | ---: |
| jackson-databind, anchor recall | 0.300 | **0.314** | 0.271 |
| jackson-databind, MRR | 0.248 | **0.250** | 0.211 |
| junit5, anchor recall | **0.273** | 0.255 | 0.255 |
| junit5, MRR | 0.211 | 0.202 | **0.224** |

**The maximum is worse than either of its inputs** on jackson-databind. That is not a tuning problem,
it is a scale problem. A pooled score is normalized by subtree length; a best-card score is not
normalized by anything about the branch at all. So a large branch almost always has *some* card
somewhere that matches a query term well, and its maximum is therefore its best-card score, while a
small focused branch's maximum is its pooled score. Which of the two signals decides a sibling's
rank is settled by how big the sibling is, not by how good its evidence is — and the resulting
numbers are not comparable across the siblings being ranked.

Neither is best-card a replacement: it is better on jackson-databind and worse on junit5, by one or
two questions each way. Nothing dominates.

## What this leaves

**Reverted.** `CardIndex` scores by pooled subtree BM25, as before, and carries a comment naming this
experiment so the repair is not attempted blind a second time.

**The bias is still real and still unaddressed.** What would actually fix it is a *calibrated*
combination — `pooled + λ · bestCard`, with the best-card term discounted by how much of the branch
you would have to search. That needs a λ, and fitting one on these four repositories is precisely
the overfitting risk already recorded against this corpus: three of the four are OSS framework code
with good Javadoc, and the profile this library targets is the opposite. A constant fitted here
would be fitted to the wrong shape.

So the honest ordering is: get an enterprise-shaped repository into the corpus, hold it out, then
calibrate. Not the reverse.

## Reproduce

```bash
repo-intel evaluate <repo> evaluation/<repo>.questions.tsv \
  --retrieval-only BM25,TREE,HYBRID --fail-under 0 --discover-classpath
```

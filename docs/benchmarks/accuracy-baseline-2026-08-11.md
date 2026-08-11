# Accuracy baseline: what the numbers actually measure

Run date: 2026-08-11

Until this run, every "accuracy" figure in this repository was **recall only**, and recall alone
cannot fail. The harness scored `hits / expected`: a question expecting two symbols scored a perfect
1.000 against an answer containing 7,946, and a tool that returned every symbol in the repository
would have scored 1.000 on every question in every set.

Two things were missing, and both are now measured.

## 1. Precision, on questions whose ground truth is complete

Precision needs the *complete* correct answer, which is expensive to write, so questions now declare
whether their expected list is exhaustive and only those are scored on it. Everything else reports
`precision not measured` rather than implying an accuracy it cannot support.

Writing the first two exhaustive questions immediately found a bug in the metric itself: precision
was being scored over the whole context packet, so `java.lang.String` and `java.math.BigDecimal`
counted as false positives on an impact question. They are things the subject *uses*, not things
*affected by changing it*. Precision is now scored over the population the question's kind implies —
impact traversal for an impact question, endpoints for an endpoint question.

| sample-commerce | value |
| --- | ---: |
| Structural accuracy (recall) | 1.000 |
| **Precision** (2 exhaustive questions) | **1.000** |
| Evidence recall | 1.000 |
| Groundedness | 1.000 |
| Median answer size | 6 symbols |

## 2. A baseline to beat

"Structural accuracy 1.000" is unfalsifiable praise on its own. The only claim worth making is
*better than searching for the name*, and that requires measuring the alternative. `evaluate
--baseline` scores a naive text search — find every file containing the subject's simple name, treat
the types those files declare as the answer — by the same rules on the same questions. That is
roughly what a search-based retriever returns before reranking.

| Repository | Graph recall | Text-search recall | Graph answer size | Baseline answer size |
| --- | ---: | ---: | ---: | ---: |
| sample-commerce | 1.000 | 0.258 | 6 | 2 |
| spring-petclinic | 1.000 | 0.000 | 25 | 8 |
| jackson-databind | 1.000 | 0.450 | **6,447** | 1,285 |

Petclinic's baseline scores zero because its expectations include derived facts — `endpoint:GET:/vets`,
module membership — that no text search can produce at all. That is the clearest illustration of what
the graph is for, and equally a reminder that those questions are easy for a graph and impossible for
grep by construction.

## The number that matters most

**jackson-databind's median answer is 6,447 symbols.** Recall is perfect and precision is unmeasured,
which together mean very little: on a repository of that size the tool currently answers "what is
affected by changing `JavaType`" with roughly a sixth of the codebase. That is arguably *correct* —
`JavaType` really is that central — but it is not *useful*, and no current metric distinguishes the
two.

This is now visible in every report rather than hidden behind a passing grade. It is the strongest
argument for ranked impact: an answer of 6,447 symbols needs an ordering, and ordering needs a metric
that rewards putting the right ones first.

## What still is not measured

- **Precision on any real repository.** Both exhaustive questions are on the 10-file fixture, where
  enumerating the complete answer is tractable. Petclinic and jackson have none, so their precision
  is genuinely unknown, not implicitly good.
- **Ranking quality.** Nothing measures whether the most relevant result is near the top, which is
  the only thing that makes a 6,447-symbol answer usable. Precision@k or MRR against a ranked ground
  truth is the missing metric.
- **A vector/embedding baseline.** BM25 and naive text search are covered; a dense-retrieval
  comparison still needs an embedding model that runs locally.
- **Negative questions.** Nothing yet asks a question whose correct answer is "nothing" or
  "unresolved", so over-claiming has no way to show up as a failure.
- **Cross-commit accuracy.** The `diff` command is tested against itself, never against a real
  upstream change with a known blast radius.
- **Cost per answer.** Latency is recorded; index build time, memory ceiling, and tokens per answer
  are not tracked over time, so a regression in any of them would be invisible.

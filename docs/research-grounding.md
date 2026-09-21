# Research grounding: what is already known about the problems we measured

Date: 2026-09-16.

**Provenance warning, first.** `arxiv.org`, `huggingface.co`, `alphaxiv.org`, `conf.researchr.org`
and every mirror tried are blocked by this environment's network policy. Everything below comes from
search-engine summaries and abstracts, **not from reading the papers**. Numbers attributed to other
authors are therefore second-hand and must be checked against the source before any of them is
repeated as fact or used to justify a design decision. Treat this document as a reading list with
annotations, not as evidence.

With that said: four of the five things this project measured the hard way are established results,
and one of our negative results is explained by a paper from 2020.

---

## 1. The 0.974 / 0.180 split is the known dominant failure mode, and it has a better name

We measured that questions naming their subject are answered 0.974 of the time and questions
describing it 0.180, falling to 0.022 on junit5.

[**The Vocabulary Gap Is an Equity Gap: Register Mismatch in Retrieval Systems for Public-Benefits
Access**](https://arxiv.org/abs/2609.01645) reports the same phenomenon in a different domain, at
almost the same magnitude: formal queries share ~0.63 of their content terms with relevant passages,
plain-language queries ~0.11 — a 5.9× reduction — and across BM25, TF-IDF and term-graph retrievers,
formal-register Recall@5 is 96–100% while plain-register Recall@5 collapses to 36–44%.

Read that against ours: 0.974 against 0.180. Their "formal register" is our "names its subject".

Two things follow.

**The phenomenon has a sharper name than we gave it: _register mismatch_.** Not a synonym problem —
the asker and the corpus are using different *registers* of the same language. That is a better
description of "switches a test off" versus `Disabled` than "vocabulary gap" is, because no synonym
table contains that pair.

**Our number is not anomalously bad.** 0.180 sits in the same band as their 36–44%, on a harder
corpus (identifiers are a more compressed register than bureaucratic prose). This reframes the
retrieval work: we are not fixing a defect peculiar to our index, we are up against the field's
standing hard problem.

Background: [Remedies against the Vocabulary Gap in Information
Retrieval](https://arxiv.org/abs/1711.06004) is the long-form treatment.
[Why Advanced Encoders Lag on Sparse Retrieval](https://arxiv.org/abs/2607.00004) argues the gap is
a solvable vocabulary-mismatch problem rather than an architectural limit — relevant if we ever make
the encoder choice.

---

## 2. Our #10 failure is explained by PARADE, and it points at the principled retry

We tried `max(pooled subtree score, best single card score)` and it came out **worse than either
input**. The diagnosis recorded in `CardIndex` was that the two quantities are not on a comparable
scale.

[**PARADE: Passage Representation Aggregation for Document
Reranking**](https://arxiv.org/abs/2008.09093) is the canonical study of exactly this — how to turn
passage-level signals into a document-level ranking. Its central finding, per the summaries:
**aggregating passage _representations_ is more effective than aggregating passage _scores_.** It
evaluates Avg, Sum, Max, Attn, CNN and Transformer aggregators over representations, and Max is
reported as the strongest of the simple ones on some datasets.

So the mistake was more specific than "max was the wrong combiner". **Max-passage is a
representation-level technique and I applied it at score level.** Element-wise max over two
representation vectors is well defined; max over two BM25 scores with different length
normalizations is not, which is precisely the incommensurability the measurement exposed.

This changes the retry from "fit a λ on four repositories" — which I declined as overfitting — to
something principled and constant-free: aggregate at the representation level once cards carry
embeddings, which is a Tier 2 question, not a Tier 0 one. **The dilution fix should wait for the
encoder rather than be attempted again in BM25 space.**

Also relevant: [The Power of Selecting Key Blocks with Local Pre-ranking for Long Document
IR](https://arxiv.org/abs/2111.09852).

---

## 3. Tier 1 is document expansion, and the literature already knows how it fails

Our planned Tier 1 — a generative model writes a description, we index it — is **document expansion
by query generation**, the doc2query family.

[Doc2Query++](https://arxiv.org/abs/2510.09557) names the failure modes, and they are the risks
written into our design revision, independently confirmed: uncontrolled generation produces
**hallucinated or redundant** text with low diversity; in-domain training **generalizes poorly**
out-of-domain; and **noise from concatenation harms dense retrieval**.

The important part is the established mitigation. **Doc2Query--** filters generated queries through a
*relevance model before indexing* rather than trusting them. That is a concrete, off-the-shelf
answer to the "unverifiable routing bias" risk I recorded as the serious one: don't index generated
text on the model's say-so, score it against the node it describes and drop what doesn't hold up.
That fits this repository's existing claims gate almost exactly — verification before admission is
already the house pattern.

See also [Doc2Token](https://arxiv.org/abs/2406.19647).

---

## 4. Our architecture is convergent with published work — we should compare, not re-derive

Three papers describe something close to what we built or planned.

[**RANGER — Repository-Level Agent for Graph-Enhanced
Retrieval**](https://arxiv.org/abs/2509.25257) is the closest: offline stage builds a knowledge
graph by **AST parsing, LLM-assisted semantic description generation, and embedding computation** —
the three tiers of our revision, in that order. It also splits queries into *code-entity queries*
and *general queries without explicit code entities*, which is our named-subject / subject-free split
under different names. If one paper is worth reading in full first, it is this one.

[**LARGER — Lexically Anchored Repository Graph Exploration and
Retrieval**](https://arxiv.org/abs/2605.16352) formalises "turn lexical matches into high-precision
structural entry points". That is the `HYBRID` thesis stated as a formalism: flat hits anchor, graph
structure expands. It also notes that graph retrieval approaches tend to **fragment the agent's
interaction loop** with separate traversal stages — a fair criticism of our `navigate` session.

[**Repository-Level Code Understanding by LLMs via Hierarchical
Summarization**](https://link.springer.com/chapter/10.1007/978-3-031-97576-9_6) (ICCSA 2025) builds
an abstract repository tree with summaries at project, directory and file level, explicitly to
improve code search and bug localization, and is explicitly motivated by "domain and vocabulary
mismatch between end-user reports and codebase semantics". This is our design, already published.

And directly on the user's enterprise concern: [**Hierarchical Repository-Level Code Summarization
for Business Applications Using Local LLMs**](https://arxiv.org/abs/2501.07857) targets *business*
applications with *local* models — both of our constraints at once.

[RAPTOR](https://arxiv.org/abs/2401.18059) is the general-domain ancestor: recursively embed,
cluster and summarize bottom-up, then retrieve at multiple abstraction levels. The instructive
difference is that **RAPTOR's parent nodes carry generated summaries**, where ours carry identifier
lists. That is the same diagnosis this project reached by measurement, stated as a design choice
someone else made deliberately in 2024.

---

## 5. CORE-Bench may dissolve our corpus blocker

Two separate pieces of work are currently blocked on "get an enterprise-shaped repository into the
corpus and hold it out": calibrating the dilution fix, and knowing whether Tier 0's Javadoc gain
survives on undocumented code.

[**CORE-Bench: A Comprehensive Benchmark for Code Retrieval in the Era of Agentic
Coding**](https://arxiv.org/abs/2606.11864) reports **180K+ queries and 106K broader-context
relevance labels**, built from curated code-search tasks and SWE-bench instances, and frames the task
as *requirement-driven repository search* — given a bug report or feature request, find the code and
context an agent should inspect. Its stated motivation is that existing benchmarks evaluate
docstring-to-function matching and miss repository context.

That is an external, independently authored, held-out evaluation set of exactly the shape we need —
and it would settle the contamination question permanently, since nobody here wrote any of it.
**Evaluating against CORE-Bench is probably higher value than hand-building a fifth repository**, and
it is the first thing I would check the licence and format of.

Related: [CodeRAG-Bench](https://arxiv.org/abs/2406.14497),
[CoIR](https://arxiv.org/abs/2407.02883).

---

## 6. A correction to something I told you about bundling

I estimated a bundled encoder at "~25 MB int8", based on general-purpose sentence encoders
(all-MiniLM-L6-v2, 22M parameters).

A **code-specific** encoder is materially larger.
[jina-embeddings-v2-base-code](https://jina.ai/models/jina-embeddings-v2-base-code/) is Apache-2.0
and ships ONNX and quantized ONNX weights — which is the right licence and the right packaging — but
it is **161M parameters, ~307 MB unquantized**. Quantized it is far smaller than that but still well
above 25 MB.

So the bundling decision is a real trade-off I understated: a small general-purpose encoder that is
cheap to ship but weaker on identifiers, or a code-trained encoder that is 5–7× the parameters. That
choice should be made on measured retrieval, not on my earlier size estimate.

---

## 7. One free source of human-language text we are not using

[Repository-level Code Search with Neural Retrieval Methods](https://arxiv.org/abs/2502.07067)
retrieves with **BM25 over commit messages**, reranked with CodeBERT, and reports up to 80%
improvement in MAP/MRR/P@1 over a BM25 baseline on 7 open-source repositories.

Commit messages are human prose, written in the asker's register, already tied to specific files,
already in every repository, and requiring no model to obtain. They are Tier 0 material by our own
definition and we index none of them. On a repository with poor Javadoc — the enterprise profile —
they may be the *only* Tier 0 prose available.

---

## What this changes, in order

1. **Read RANGER (2509.25257) in full first.** It is the closest published architecture.
2. **Check CORE-Bench's licence and format.** If usable, it replaces the hand-built enterprise
   corpus and removes the contamination question from two blocked workstreams.
3. **Index commit messages as Tier 0.** Free, no model, and plausibly the only prose in enterprise
   repositories.
4. **Defer the dilution fix to the encoder.** PARADE says aggregate representations, not scores;
   there is no correct score-level constant to find, so stop looking for one in BM25 space.
5. **Adopt Doc2Query-- style filtering for Tier 1** when it is built — verify generated descriptions
   against the node before indexing, never on the generator's say-so.
6. **Re-open the encoder size question** with real numbers rather than my estimate.

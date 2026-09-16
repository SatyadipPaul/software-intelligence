# Static encoders: the same quality, without the runtime

Date: 2026-09-16. JDK 25, the 200-question corpus, build classpaths supplied, `DENSE_HYBRID`
throughout.

[The dense encoder run](dense-encoder-2026-09-16.md) established that retrieval by meaning beats
retrieval by shared words, using `all-MiniLM-L6-v2` over ONNX Runtime. That was the **quality
reference**, not a shipping candidate: it needs a native runtime, and a per-platform binary is a
heavy thing to inflict on consumers of a library.

This run measures the candidates that need no runtime at all.

## What a static model is

Model2Vec's `potion-*` models are distilled into a **vocabulary-to-vector table**. There is no
forward pass: tokenize, look up one vector per token, drop the special tokens, average, normalise.
The whole implementation is one short class plus a safetensors reader, with no ONNX, no JNI and no
per-platform artifacts. Every measurement below was produced with the ONNX runtime **absent from the
classpath**.

## The result

Subject-free questions — the 161 of 200 that describe what code does rather than naming it:

| Encoder | dims | subject-free | vs Tier 0 (0.304) |
| --- | ---: | ---: | --- |
| *(lexical only)* | — | 0.304 | — |
| `potion-base-8M` | 256 | 0.379 | +25% |
| `all-MiniLM-L6-v2` | 384 | 0.404 | +33% |
| **`potion-base-32M`** | 512 | **0.410** | **+35%** |

**The best static model matches the transformer, and does it without a runtime.**

Per repository, `DENSE_HYBRID`:

| Repository | Metric | BM25 | all-MiniLM | potion-8M | potion-32M |
| --- | --- | ---: | ---: | ---: | ---: |
| sample-commerce | anchor recall | 0.760 | **0.920** | 0.800 | 0.840 |
| sample-commerce | MRR | 0.560 | **0.690** | 0.641 | 0.655 |
| spring-petclinic | anchor recall | 0.520 | 0.660 | 0.660 | **0.700** |
| spring-petclinic | MRR | 0.378 | **0.560** | 0.531 | 0.552 |
| jackson-databind | anchor recall | 0.329 | 0.386 | 0.400 | **0.429** |
| jackson-databind | MRR | 0.266 | 0.306 | **0.345** | 0.340 |
| junit5 | anchor recall | 0.345 | **0.382** | 0.345 | 0.345 |
| junit5 | MRR | 0.257 | **0.303** | 0.274 | 0.287 |

No encoder dominates. all-MiniLM wins the fixture and junit5; the static models win
jackson-databind and Petclinic's anchor recall. The spread between them is smaller than the spread
between any of them and lexical retrieval, which is the finding that matters: **the choice of
encoder is a second-order decision, and having one at all is the first-order one.**

## Cost, which is not second-order

| Encoder | On disk (fp32) | Embedding 6,714 nodes | Native runtime |
| --- | ---: | ---: | --- |
| `all-MiniLM-L6-v2` | 87 MB | 18,406 ms | **ONNX, per-platform** |
| `potion-base-8M` | 30 MB | **154 ms** | none |
| `potion-base-32M` | 124 MB | 494 ms | none |

`potion-base-8M` indexes jackson-databind **120× faster** than the transformer; `potion-base-32M`,
**37× faster**. On junit5 the gap is 10,718 ms against 129 ms. Indexing stops being a cost worth
planning around.

Note the disk figures are **fp32 as published**. The commonly quoted "~8 MB" and "~30 MB" for these
models are parameter counts or quantized forms; measured on disk, unquantized, they are 30 MB and
124 MB. Quantization is the obvious next lever and is unexplored here.

## Recommendation

**`potion-base-32M` for quality, `potion-base-8M` if size rules.** The 32M model matches the
transformer reference on the headline metric while needing no runtime; the 8M model gives up 8% of
that lift for a quarter of the disk and another 3× in speed.

The transformer stays supported and stays unshipped: it is the reference that says what the static
models cost, and an operator who wants it can point `--embedding-model` at one.

## Two things found on the way

**A defect in the reference implementation.** The npm package publishing `potion-base-32M` hardcodes
BERT's special-token ids `{0, 100, 101, 102, 103}` while its own tokenizer uses `{0, 1, 2, 3, 4}`.
It therefore averages `[CLS]` and `[SEP]` into every vector and excludes three ordinary words. The
implementation here resolves special tokens **by name**, so it is unaffected — but it means that
package could not serve as a conformance reference for the 32M model, and the numbers above are
unverified against an independent implementation for that model alone.

**Conformance was verified for `potion-base-8M`.** Against the 8M reference implementation, which
uses the correct ids, this implementation reproduces the embedding to six decimal places on every
component, and both similarity scores. That is what gives confidence in the 32M numbers despite the
above: the same code path, verified on the model whose reference is correct.

**`potion-retrieval-32M` could not be obtained.** It is the retrieval-tuned variant and was the
leading candidate on paper; it is not published anywhere this environment can reach. It remains
worth measuring and may beat both.

## Reproduce

```bash
# model directory: model.safetensors + vocab.txt (vocab extracted from the tokenizer.json)
repo-intel evaluate <repo> evaluation/<repo>.questions.tsv \
  --retrieval-only BM25,DENSE_HYBRID --fail-under 0 \
  --discover-classpath --embedding-model <model-dir>
```

No ONNX runtime is needed on the classpath for a static model, which is the point.

---

# Addendum: demoting test code

Date: 2026-09-16, same corpus and method, `potion-base-32M`.

The run above recorded a confound and left it open: **dense retrieval is swamped by test code on a
testing framework.** The first junit5 run put `TestAnnotation`, `AnnotationUtilsTests` and
`LifecycleMethodTests` above the API for nearly every question, and excluding test sources moved
subject-free recall@1 from 0.065 to 0.239 on identical questions.

This is a dense-specific failure. A term index barely notices it, because an exact name cuts
through; an encoder has nothing to cut through with, because the test genuinely *is* about the same
subject as the question. Ranking by meaning ranks a test of X near a question about X — correctly,
and uselessly.

## The fix

`DenseIndex` now demotes test nodes, **reusing the weight and the detection that
`BranchEnrichment` already applied to branch ranking** rather than introducing a second opinion.
Two details were worth getting right:

- **Demoted, not dropped.** A test is sometimes exactly what a reader wants — "what covers this?" —
  so it stays reachable and simply never wins when production code fits.
- **The demotion is sign-safe.** Multiplying a signed score by a factor below one moves a *negative*
  similarity up: −0.4 × 0.1 is −0.04, which outranks −0.4. Taking the smaller of the two keeps the
  demotion monotone whatever the sign. Cosine similarity is genuinely signed, so this is a real bug
  avoided rather than a hypothetical one, and there is a test pinning it.

Detection was also unified on the way. Two implementations had drifted: enrichment knew about
`src/test` and `src/testFixtures`, the analyzer also knew about `src/it` and both spellings of an
integration-test root. `TestCode` is the union, so a branch and a vector now agree about what a test
is.

## The result

Subject-free questions, `DENSE_HYBRID` with `potion-base-32M`:

| Repository | before | after | change |
| --- | ---: | ---: | --- |
| sample-commerce | 0.765 | 0.765 | — *(no test sources: nothing to demote)* |
| spring-petclinic | 0.605 | 0.632 | +4% |
| jackson-databind | 0.333 | **0.483** | **+45%** |
| junit5 | 0.217 | **0.391** | **+80%** |
| **all 200** | **0.410** | **0.522** | **+27%** |

Headline metrics:

| Repository | anchor recall | recall@1 | MRR |
| --- | --- | --- | --- |
| spring-petclinic | 0.700 → **0.720** | 0.460 → **0.520** | 0.552 → **0.602** |
| jackson-databind | 0.429 → **0.557** | 0.286 → **0.357** | 0.340 → **0.431** |
| junit5 | 0.345 → **0.491** | 0.255 → **0.273** | 0.287 → **0.336** |

Named-subject questions stay at **39/39**, and the lexical modes are unchanged to three decimals —
BM25 on junit5 is 0.345/0.218/0.257 before and after — because nothing outside `DenseIndex` moved.

Subject-free retrieval has now gone **0.180 → 0.304 → 0.404 → 0.522** across Tier 0 Javadoc, the
encoder, and this: **2.9× the lexical baseline.**

## What this measurement cannot tell us

Every question in the corpus is grounded in a **production** symbol, by construction. So a corpus
like this one can only reward demoting test code; it is structurally incapable of detecting the harm
if the demotion is too strong. The counter-case — "which tests cover `PaymentService`?" — is exactly
the question type the corpus does not contain.

That is why the demotion is a demotion and not an exclusion, and why the weight was taken from an
existing shipped decision rather than fitted here. Fitting it on these 200 questions would have
driven it to zero and looked like an improvement.

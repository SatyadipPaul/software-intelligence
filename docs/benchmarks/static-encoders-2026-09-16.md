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

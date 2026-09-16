# Choosing an encoder: what the market actually ships

Date: 2026-09-16.

**Provenance warning.** `huggingface.co` and `arxiv.org` are blocked by this environment's network
policy. Every specification below comes from search-engine summaries, not from a model card, a
licence file or a benchmark table. **Nothing here is verified.** Sizes, licences and scores must be
confirmed against primary sources before any of it drives a distribution decision — a wrong licence
claim in a published library is a legal problem, not a performance one.

## What locally-runnable graph-RAG systems converge on

| System | Default local embedding model | Dims | Params |
| --- | --- | ---: | ---: |
| Chroma | `all-MiniLM-L6-v2` | 384 | ~22.7M |
| Qdrant FastEmbed | `BAAI/bge-small-en-v1.5` | 384 | ~33M |
| Neo4j GraphRAG | `all-MiniLM-L6-v2` | 384 | ~22.7M |
| nano-graphrag (local option) | `all-MiniLM-L6-v2` | 384 | ~22.7M |
| Microsoft GraphRAG | OpenAI `text-embedding-3-large` | — | API only |
| LightRAG | OpenAI `text-embedding-3-large` | — | API only |

Two things fall out.

**Everything that runs locally has converged on the same point:** 384 dimensions, 22–33M
parameters, BERT-family. That is a strong market signal — it is the size the ecosystem has decided
is worth shipping — and it is an order of magnitude below the code-specific encoder this project was
previously considering (`jina-embeddings-v2-base-code`, ~161M parameters, ~307 MB unquantized).

**Everything larger is an API**, which the local-first invariant rules out. Microsoft GraphRAG and
LightRAG default to OpenAI endpoints; those defaults are not available to us at any size.

## The smaller frontier: static embeddings

The market default is not the floor. **Model2Vec** (MIT) distils any sentence transformer into a
*static* model — a vocabulary-to-vector table rather than a network:

| Model | Size on disk | Params | Dims | Vocab |
| --- | ---: | ---: | ---: | ---: |
| `potion-base-2M` | ~2 MB class | — | — | — |
| `potion-base-8M` | **~8 MB** | ~7.5M | 384 | 30k |
| `potion-base-32M` | ~30 MB | — | — | — |
| `potion-retrieval-32M` | ~30 MB | — | — | — |

Reported: up to 50× smaller and up to 500× faster on CPU than the source transformer, distilled from
`bge-base-en-v1.5`, and comfortably ahead of GloVe/word2vec/fastText — the previous generation of
static embeddings — on MTEB.

### Why this changes our decision rather than merely shrinking it

The open risk in the encoder plan was never size, it was **runtime**. ONNX Runtime is Apache-2.0 but
drags per-platform native binaries; DJL is heavier; and the pure-Java transformer path was an
estimate I had explicitly flagged as unmeasured.

A static model has no forward pass. Inference is: tokenize, look up one vector per token, mean-pool,
normalize. That is a few hundred lines of pure Java with **no native dependency, no ONNX, no
incubator Vector API flag, and no per-platform artifacts** — which is precisely the shape an
open-source library can publish without inflicting a native toolchain on every consumer.

The single largest unknown in the encoder decision disappears. That is worth more to this project
than a few MTEB points.

### What it costs, and what must be measured

A static embedding gives a token **one vector regardless of context**. `Test` means the same thing
in every sentence. Contextual encoders exist because that is often wrong.

The bet is that it is *not* wrong for our problem. The measured failure is register mismatch at the
term level — "switches a test off" against `Disabled`, "at run time" against `Dynamic` — and
term-level synonymy is exactly what a static table encodes. We are not asking it to resolve
compositional meaning; we are asking it to bridge two registers of the same vocabulary. **That is a
hypothesis with a mechanism, and it is the thing the measurement has to test**, not an assumption to
build on.

## Numbers I could not reconcile

The summaries give **`potion-base-8M` an MTEB average of 56.3** and **`potion-base-32M` an average
of 52.83, described as 94.66% of `all-MiniLM-L6-v2`**. Those cannot both be on the same scale: if
52.83 is 94.66% of MiniLM then MiniLM is ~55.8, and the 8M model would then beat both it and the
larger potion model, while the same sources call the 32M model the most performant static embedding
available.

Almost certainly different MTEB subsets or versions. **Treat the internally consistent pair — 32M at
~94.66% of `all-MiniLM-L6-v2` — as the working estimate, and treat 56.3 as unverified.** Resolve it
against the model cards before it influences anything.

Note also that `potion-retrieval-32M` exists and is *retrieval-tuned*. For this project that is the
interesting variant, not the general-purpose one, and it barely appears in the summaries.

## Measured so far

`all-MiniLM-L6-v2` has been run on the full corpus: subject-free retrieval 0.304 → **0.404**, and
`DENSE_HYBRID` beats flat retrieval on all four corpora on every headline metric. Embedding costs
~2.7 ms per node, once per graph, over the ~12% of nodes worth anchoring on. That is the **ceiling**
the static candidates have to approach — see
[the dense encoder run](benchmarks/dense-encoder-2026-09-16.md). It is not a shipping decision: it
needs a native runtime, which is the thing the static models avoid.

## Measured: all three, and the recommendation changed

Every candidate below has now been run on the full corpus. Subject-free retrieval: `potion-base-32M`
**0.410**, `all-MiniLM-L6-v2` 0.404, `potion-base-8M` 0.379, against 0.304 for lexical alone. The
static models need no native runtime and index 37-120x faster. **Recommendation: `potion-base-32M`,
or `potion-base-8M` where size rules.** The transformer stays supported and unshipped as the
reference. `potion-retrieval-32M` could not be obtained from any reachable host and remains
unmeasured. Measured sizes on disk, fp32 as published, differ from the figures quoted below: 30 MB
for the 8M model and 124 MB for the 32M, not the ~8/~30 MB that parameter-count summaries imply.
See [the static encoders](benchmarks/static-encoders-2026-09-16.md).

## The original plan, kept for the record

Measure three, ship one:

1. **`potion-retrieval-32M`** — retrieval-tuned, ~30 MB, no native runtime. The leading candidate.
2. **`potion-base-8M`** — the size floor at ~8 MB. If it holds up, the encoder stops being a
   packaging question entirely.
3. **`all-MiniLM-L6-v2` via ONNX** — not to ship, but as the **quality reference**. It is what the
   market ships locally, so it tells us what the static models cost us. If the gap is small, the
   static path wins on distribution alone; if it is large, we know what we are trading.

The code-specific encoder (`jina-embeddings-v2-base-code`, Apache-2.0, 161M params) stays on the
list as the upper bound on quality, but at 5–7× the parameters and with a native runtime it is the
option that has to *earn* its cost against the others, not the default.

Packaging stays as designed regardless of which wins: a **separate optional artifact**, with the
core degrading to lexical scoring when it is absent.

## To verify before committing

- [ ] Model2Vec licence (MIT?) **and** the individual `potion-*` model weights' licences — these can
      differ from the library's, and a distilled model may inherit terms from its source.
- [ ] `bge-base-en-v1.5`'s licence, since the potion models are distilled from it.
- [ ] Real on-disk sizes and vocabulary sizes from the model cards.
- [ ] The MTEB discrepancy above.
- [ ] Which tokenizer the potion models require. They are BERT-family, so WordPiece with a ~30k
      vocab is likely, which is a few hundred lines of Java. Confirm rather than assume — the
      tokenizer is the only part of a static encoder that is not trivial.
- [ ] That no Java port already exists. The searches found a Rust server and the Python package, but
      absence of evidence here is weak: these searches could not reach the package registries.

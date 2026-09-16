# A dense encoder, and the first thing to beat lexical retrieval everywhere

Date: 2026-09-16. JDK 25, the 200-question corpus, build classpaths supplied,
`sentence-transformers/all-MiniLM-L6-v2` (Apache-2.0, 384 dimensions, ~22.7M parameters) over ONNX
Runtime.

Six interventions have now been measured. Four moved nothing, one moved the number, and this one
moves it furthest.

## The result

Subject-free questions — the 161 of 200 that describe what code does rather than naming it:

| | subject-free | change |
| --- | ---: | --- |
| baseline (identifiers only) | 0.180 | — |
| + Tier 0 Javadoc | 0.304 | +69% |
| **+ dense encoder (`DENSE_HYBRID`)** | **0.404** | **+124% on baseline, +33% on Tier 0** |

Questions that name their subject: **39/39 = 1.000**. Nothing was traded for it.

Per repository, all four question sets:

| Repository | Mode | anchor recall | recall@1 | MRR |
| --- | --- | ---: | ---: | ---: |
| sample-commerce | BM25 | 0.760 | 0.440 | 0.560 |
| sample-commerce | HYBRID | 0.800 | 0.560 | 0.647 |
| sample-commerce | **DENSE_HYBRID** | **0.920** | 0.560 | **0.690** |
| spring-petclinic | BM25 | 0.520 | 0.300 | 0.378 |
| spring-petclinic | HYBRID | 0.560 | 0.440 | 0.486 |
| spring-petclinic | **DENSE_HYBRID** | **0.660** | **0.500** | **0.560** |
| jackson-databind | BM25 | 0.329 | 0.229 | 0.266 |
| jackson-databind | HYBRID | 0.314 | 0.214 | 0.250 |
| jackson-databind | **DENSE_HYBRID** | **0.386** | **0.271** | **0.306** |
| junit5 | BM25 | 0.345 | 0.218 | 0.257 |
| junit5 | HYBRID | 0.291 | 0.200 | 0.227 |
| junit5 | **DENSE_HYBRID** | **0.382** | **0.255** | **0.303** |

**`DENSE_HYBRID` is the first mode to beat flat retrieval on every corpus on every headline metric.**
Milestone 6's exit criterion, which `HYBRID` failed on 200 questions, is met by this mode.

## Why `DENSE` alone is not the answer

| junit5 | anchor recall | recall@1 | MRR |
| --- | ---: | ---: | ---: |
| BM25 | 0.345 | 0.218 | 0.257 |
| DENSE | 0.345 | **0.145** | 0.219 |
| DENSE_HYBRID | 0.382 | 0.255 | 0.303 |

Dense alone ties flat retrieval on reach and is *worse* on rank, because it loses the 39 questions
that name their subject outright — where an exact name match is proof and a similarity score is a
guess. The ordering that works puts the named subject first, dense second, and the flat hits last:
proof, then the signal that can reach an unnamed symbol, then the one that cannot.

That is the same lesson as the earlier `HYBRID` result, and it survived a change of signal.

## What the tree keeps

Anchor coverage — the only metric that scores a *set* rather than one symbol — still belongs to the
descent: 0.246 against dense's 0.179 on Petclinic, 0.250 against 0.214 on the fixture. A descent
lands on containers; dense ranking lands on individual nodes that score well. The two are not
competing for the same job, and a mode that combines descent *and* dense is unmeasured.

## Two confounds found on the way

**Test sources swamp dense retrieval on a testing framework.** A first run over all junit5 types put
`TestAnnotation`, `AnnotationUtilsTests` and `LifecycleMethodTests` above the API for nearly every
question. In a testing framework the test tree is genuinely, semantically about testing, so it wins
on meaning while losing on relevance. Excluding test sources moved subject-free recall@1 from 0.065
to 0.239 on the same questions. Lexical retrieval is much less exposed to this, because exact names
cut through it.

**Card text must be split before it is embedded.** `BeforeEachCallback` is one unknown token to a
WordPiece tokenizer and three known ones as "Before Each Callback" — and only the second can be near
"runs before every test". The splitting that already existed for BM25 turns out to matter more for
an encoder than for a term index.

## Cost

| Repository | anchorable nodes | embedding time | per node |
| --- | ---: | ---: | ---: |
| sample-commerce | 18 | 54 ms | 3.0 ms |
| spring-petclinic | 78 | 202 ms | 2.6 ms |
| junit5 | 4,026 | 10.7 s | 2.7 ms |
| jackson-databind | 6,714 | 18.4 s | 2.7 ms |

Once per graph, not per question, and only for nodes worth anchoring on — types and the containers
above them, about an eighth of the graph. Query latency rises modestly: 60 ms to 78 ms on
jackson-databind, 37 ms to 46 ms on junit5.

## What this does not settle

**The encoder to ship is still open.** `all-MiniLM-L6-v2` was measured because it is what the market
runs locally — Chroma, Neo4j GraphRAG and nano-graphrag all default to it — and because ONNX Runtime
is on Maven Central. It is the **quality reference**, not the recommendation. The static models
(`potion-retrieval-32M`, `potion-base-8M`) are the candidates for shipping, because they need no
native runtime at all, and they are unmeasured. This run establishes the ceiling they have to
approach.

**Nothing here is bundled.** The encoder is an optional module; the ONNX runtime is an optional
dependency of it; the weights are supplied by the operator with `--embedding-model`. Without one,
every existing mode behaves exactly as before, which is the local-first invariant intact.

**The contamination caveat stands**, as it now does for everything: the questions and the code share
an author. What protects this particular result is that the encoder is third-party and was trained
on neither.

## Reproduce

```bash
# weights: sentence-transformers/all-MiniLM-L6-v2, as model.onnx + vocab.txt in one directory
java -cp repo-intel.jar:onnxruntime-1.20.0.jar io.softwareintelligence.cli.Main \
  evaluate <repo> evaluation/<repo>.questions.tsv \
  --retrieval-only BM25,HYBRID,DENSE,DENSE_HYBRID --fail-under 0 \
  --discover-classpath --embedding-model <model-dir>
```

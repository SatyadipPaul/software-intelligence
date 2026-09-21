# `embedding-model` — packaged weights

This module turns a quantized static embedding model into a Maven artifact. A consumer who adds it
gets dense retrieval with no flag, no path and no download; a consumer who does not is unaffected.

It ships **no code**. `EncoderFactory.packaged()` looks for two files at a fixed resource path:

```
repo-intel/embedding/model.safetensors
repo-intel/embedding/vocab.txt
```

## The weights are not in this repository

`src/main/resources/repo-intel/embedding/` holds no weights, and the module only builds when it
does — the root POM activates it on the presence of `model.safetensors`. So a clone builds and tests
normally without carrying tens of megabytes of binary, and `mvn -Pembedding-model` is unnecessary:
populate the directory and the module joins the build by itself.

That is a deliberate choice rather than an oversight. **Redistributing someone else's model weights
is a licensing act**, and this project's rule is that everything in the bundle is open source and
provably so.

## The build refuses weights that do not say whose they are

Two files must sit beside the weights, and the module fails to build without them:

| File | What it holds |
| --- | --- |
| `LICENSE` | The model's own licence text, copied from its model card or repository. |
| `PROVENANCE.md` | Model name, upstream URL, the revision pinned, what it was distilled from, and that parent's licence. |

Both are committed and both travel inside the artifact, so a consumer who unzips the jar can see
whose work the weights are without going back to a web page that may have changed.

This used to be a checklist in this file. A checklist is a thing a hurried maintainer skips; a build
failure is not. The rule lives in this module's POM as `weights-must-carry-their-licence`.

## Producing the weights

```bash
# 1. obtain a static Model2Vec model from its own upstream, at a pinned revision:
#    model.safetensors (F32) + vocab.txt, plus the model card that states the licence
#    (vocab.txt is the tokenizer's vocabulary, one token per line, in id order)

# 2. quantize it — roughly a quarter of the size, no measurable quality cost
repo-intel quantize-model /path/to/model --output modules/embedding-model/src/main/resources/repo-intel/embedding

# 3. write the two legal files beside the weights
#    modules/embedding-model/src/main/resources/repo-intel/embedding/LICENSE
#    modules/embedding-model/src/main/resources/repo-intel/embedding/PROVENANCE.md

# 4. the module now builds
mvn -q install
```

`potion-base-32M` quantizes to about 33 MB and scored 0.522 on the subject-free corpus;
`potion-base-8M` to about 8 MB and 0.466. See
[the static encoder benchmark](../../docs/benchmarks/static-encoders-2026-09-16.md).

## What is still unverified, and why it matters

The measurements in this repository were run against `potion-base-32M` weights obtained from an
**npm re-packaging** of the model, because the model host is unreachable from the environment the
benchmarks ran in. That package's `package.json` says `"license": "MIT"` — and that is the
*wrapper's* licence, authored by the re-packager, describing a few hundred lines of JavaScript. It
says nothing about the weights, which the package carries but does not license. The re-package
contains no model card.

So the licence of the weights those numbers came from has **not been read from the model's own
sources**, and until it has, they are not publishable. The benchmarks remain valid — they measure
retrieval quality, not provenance — and are reproducible by anyone who fetches the model from
upstream and points `--embedding-model` at it.

When verifying, read from the model's own card or repository:

1. The model's licence, and whether it permits redistribution in a compiled artifact.
2. The licence of the model it was distilled from, since a distilled model may inherit terms.
   `potion-base-32M`'s `config.json` names `baai/bge-base-en-v1.5` as its tokenizer source.
3. The revision to pin, so the artifact says exactly what it holds.
4. Attribution, into `PROVENANCE.md` and the artifact's POM, whether or not the licence demands it.

Quantizing changes the bytes but not the provenance: a quantized model is a derivative work of the
original and carries its terms.

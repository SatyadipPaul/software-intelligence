# `embedding-model` — packaged weights

This module turns a quantized static embedding model into a Maven artifact. A consumer who adds it
gets dense retrieval with no flag, no path and no download; a consumer who does not is unaffected.

It ships **no code**. `EncoderFactory.packaged()` looks for two files at a fixed resource path:

```
repo-intel/embedding/model.safetensors
repo-intel/embedding/vocab.txt
```

## The weights are not in this repository

`src/main/resources/repo-intel/embedding/` is empty, and the module only builds when it is not — the
root POM activates it on the presence of `model.safetensors`. So a clone builds and tests normally
without carrying tens of megabytes of binary, and `mvn -Pembedding-model` is unnecessary: populate
the directory and the module joins the build by itself.

That is a deliberate choice rather than an oversight. Redistributing someone else's model weights is
a licensing act, and the checklist below has **not** been completed in this repository. Do not
publish this artifact until it has.

## Producing the weights

```bash
# 1. obtain a static Model2Vec model: model.safetensors (F32) + vocab.txt
#    (vocab.txt is the tokenizer's vocabulary, one token per line, in id order)

# 2. quantize it — roughly a quarter of the size, no measurable quality cost
repo-intel quantize-model /path/to/model --output modules/embedding-model/src/main/resources/repo-intel/embedding

# 3. the module now builds
mvn -q install
```

`potion-base-32M` quantizes to about 33 MB and scored 0.522 on the subject-free corpus;
`potion-base-8M` to about 8 MB and 0.466. See
[the static encoder benchmark](../../docs/benchmarks/static-encoders-2026-09-16.md).

## Before publishing, verify

Every item is about **someone else's weights**, and none of it can be taken from a summary:

- [ ] The model's own licence, read from its model card or repository — **not** from a wrapper
      package's `package.json`, which describes the wrapper.
- [ ] The licence of the model it was distilled from, since a distilled model may inherit terms.
- [ ] That the licence permits redistribution in a compiled artifact, and on what conditions.
- [ ] Attribution: add the model name, version, source URL and licence to `NOTICE` beside this file
      and to the artifact's POM, whether or not the licence demands it.
- [ ] Whether a pinned revision is available, and pin it, so the artifact says exactly what it holds.

Quantizing changes the bytes but not the provenance: a quantized model is a derivative work of the
original and carries its terms.

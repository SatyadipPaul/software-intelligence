package io.softwareintelligence.embedding;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A sentence-transformer encoder over ONNX Runtime: tokenize, run the model, mean-pool, normalise.
 *
 * <p>Loaded reflectively by {@link EncoderFactory} so that the ONNX runtime stays an optional
 * dependency. Nothing outside this class names an {@code ai.onnxruntime} type, which is what lets a
 * consumer who never asks for a dense encoder avoid downloading 90 MB of per-platform binaries.
 *
 * <p>Mean pooling over the unmasked tokens, not the {@code [CLS]} vector: that is how
 * sentence-transformers models are trained to be read, and taking {@code [CLS]} instead produces
 * vectors that are the right shape and the wrong meaning.
 */
final class OnnxTextEncoder implements TextEncoder {

    /** Token cap per text. Cards are short; this bounds the pathological ones rather than the norm. */
    private static final int MAX_TOKENS = 128;
    /** Texts per inference call. Large enough to amortise the call, small enough to bound memory. */
    private static final int BATCH = 32;

    private final WordPieceTokenizer tokenizer;
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final boolean needsTokenTypes;
    private final int dimensions;

    OnnxTextEncoder(Path modelDirectory) {
        Path model = modelDirectory.resolve("model.onnx");
        Path vocabulary = modelDirectory.resolve("vocab.txt");
        if (!Files.isRegularFile(model) || !Files.isRegularFile(vocabulary)) {
            throw new EncoderUnavailableException(
                    "expected model.onnx and vocab.txt in " + modelDirectory.toAbsolutePath());
        }
        try {
            this.tokenizer = WordPieceTokenizer.fromVocabulary(vocabulary);
            this.environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors());
            this.session = environment.createSession(model.toString(), options);
            // Some exports drop token_type_ids; supplying an input the graph does not declare is an
            // error, so ask the model rather than assuming either way.
            this.needsTokenTypes = session.getInputInfo().containsKey("token_type_ids");
            this.dimensions = encodeOne("probe").length;
        } catch (IOException | OrtException failure) {
            throw new EncoderUnavailableException("could not load the encoder from " + modelDirectory, failure);
        }
    }

    @Override public int dimensions() { return dimensions; }

    @Override
    public float[][] encode(List<String> texts) {
        float[][] vectors = new float[texts.size()][];
        for (int from = 0; from < texts.size(); from += BATCH) {
            encodeBatch(texts, from, Math.min(texts.size(), from + BATCH), vectors);
        }
        return vectors;
    }

    private void encodeBatch(List<String> texts, int from, int to, float[][] into) {
        List<int[]> encoded = new ArrayList<>(to - from);
        int width = 1;
        for (int i = from; i < to; i++) {
            int[] tokens = tokenizer.encode(texts.get(i), MAX_TOKENS);
            encoded.add(tokens);
            width = Math.max(width, tokens.length);
        }
        int rows = encoded.size();
        long[] ids = new long[rows * width];
        long[] mask = new long[rows * width];
        for (int row = 0; row < rows; row++) {
            int[] tokens = encoded.get(row);
            for (int column = 0; column < tokens.length; column++) {
                ids[row * width + column] = tokens[column];
                mask[row * width + column] = 1L;
            }
        }
        long[] shape = {rows, width};
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            inputs.put("input_ids", OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape));
            inputs.put("attention_mask", OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape));
            if (needsTokenTypes) {
                inputs.put("token_type_ids",
                        OnnxTensor.createTensor(environment, LongBuffer.wrap(new long[rows * width]), shape));
            }
            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] hidden = (float[][][]) result.get(0).getValue();
                for (int row = 0; row < rows; row++) {
                    into[from + row] = pool(hidden[row], encoded.get(row).length);
                }
            }
        } catch (OrtException failure) {
            throw new EncoderUnavailableException("the encoder failed while running", failure);
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
    }

    /** Mean over the real tokens, then L2 normalise, so similarity is a dot product. */
    private static float[] pool(float[][] tokens, int length) {
        float[] pooled = new float[tokens[0].length];
        for (int token = 0; token < length; token++) {
            for (int d = 0; d < pooled.length; d++) pooled[d] += tokens[token][d];
        }
        double norm = 0.0;
        for (int d = 0; d < pooled.length; d++) {
            pooled[d] /= length;
            norm += (double) pooled[d] * pooled[d];
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int d = 0; d < pooled.length; d++) pooled[d] /= (float) norm;
        }
        return pooled;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException ignored) {
            // Closing a session that already failed should not mask the original failure.
        }
    }
}

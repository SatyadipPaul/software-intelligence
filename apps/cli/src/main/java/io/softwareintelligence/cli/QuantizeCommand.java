package io.softwareintelligence.cli;

import io.softwareintelligence.embedding.EncoderFactory;
import io.softwareintelligence.embedding.Quantizer;
import io.softwareintelligence.embedding.TextEncoder;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Shrinks a static embedding model to int8, so weights small enough to ship get smaller still.
 *
 * <p>An offline step with a durable output: quantise once, point {@code --embedding-model} at the
 * result forever. It is a command rather than a script because the output is something an operator
 * ships, and a shipped artifact should come out of a tested path.
 */
@CommandLine.Command(mixinStandardHelpOptions = true, hidden = true, name = "quantize-model",
        description = "Quantize a static embedding model to int8, roughly quartering it on disk.")
public final class QuantizeCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "Model directory holding model.safetensors and vocab.txt")
    private Path source;

    @CommandLine.Option(names = "--output", required = true, description = "Directory to write the quantized model to")
    private Path target;

    @Override
    public Integer call() throws Exception {
        long before = Files.size(source.resolve("model.safetensors"));
        double worst = Quantizer.quantize(source, target);
        long after = Files.size(target.resolve("model.safetensors"));

        // Load it back and report what it costs on real text, because "the weights moved by under
        // 0.4%" is a statement about weights, and what a caller cares about is vectors.
        double drift;
        try (TextEncoder original = EncoderFactory.open(source);
             TextEncoder quantized = EncoderFactory.open(target)) {
            drift = worstSimilarityDrift(original, quantized);
        }

        System.out.printf("quantized %s -> %s%n", source, target);
        System.out.printf("  size            %.1f MB -> %.1f MB  (%.1fx smaller)%n",
                before / 1e6, after / 1e6, before / (double) after);
        System.out.printf("  worst weight error   %.4f  (bound %.4f)%n", worst, Quantizer.maximumRelativeError());
        System.out.printf("  worst cosine drift   %.6f  on the probe texts%n", drift);
        System.out.println("  rankings can still move: re-run the benchmark against the quantized model");
        return 0;
    }

    /** How far the quantized encoder's vectors sit from the original's, on text of the shape it indexes. */
    private static double worstSimilarityDrift(TextEncoder original, TextEncoder quantized) {
        List<String> probes = List.of(
                "Which annotation switches a test off without deleting it?",
                "Disabled. Signals that the annotated test class or test method is currently disabled",
                "Object Mapper. Reads and writes JSON, and is the entry point for data binding",
                "Owner Repository. Finds the clients of the clinic by last name",
                "a");
        float[][] left = original.encode(probes);
        float[][] right = quantized.encode(probes);
        double worst = 0;
        for (int i = 0; i < probes.size(); i++) {
            worst = Math.max(worst, 1.0 - TextEncoder.similarity(left[i], right[i]));
        }
        return worst;
    }
}

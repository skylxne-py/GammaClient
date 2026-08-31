package dev.gamma.chunks.model;

import java.util.Map;

/**
 * Output of {@link dev.gamma.chunks.classifier.ClassifierPipeline#classify}. {@code confidence}
 * is in {@code [0,1]}, 1 meaning "confidently a freshly generated chunk". {@code signals} carries
 * each classifier's own clamped {@code [-1,1]} vote, keyed by {@link dev.gamma.chunks.classifier.ChunkClassifier#id()},
 * for {@code .chunks query} / debugging — classifiers that abstained (returned NaN) are absent.
 */
public record ClassificationResult(Classification classification, double confidence, Map<String, Double> signals) {
}

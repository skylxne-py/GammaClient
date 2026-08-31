package dev.gamma.chunks.classifier;

import dev.gamma.chunks.model.ChunkObservation;
import dev.gamma.chunks.model.Classification;
import dev.gamma.chunks.model.ClassificationResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Combines every registered {@link ChunkClassifier}'s weighted vote into one
 * {@link ClassificationResult}. Classifiers that abstain (NaN) are excluded from both the
 * weighted sum and the reported signal map, so a chunk with e.g. no containers at all still
 * gets a real verdict from whichever classifiers did have an opinion.
 */
public final class ClassifierPipeline {

	private static final double NEW_THRESHOLD = 0.6;
	private static final double EXISTING_THRESHOLD = 0.4;

	private final List<ChunkClassifier> classifiers;

	public ClassifierPipeline(List<ChunkClassifier> classifiers) {
		this.classifiers = List.copyOf(classifiers);
	}

	/** The default pipeline, weights per {@code docs/CLASSIFIERS.md}. */
	public static ClassifierPipeline standard() {
		return new ClassifierPipeline(List.of(
				new GenerationLatencyClassifier(),
				new LiquidSettlingClassifier(),
				new PostLoadUpdateBurstClassifier(),
				new LightingCoverageClassifier(),
				new UnrolledLootClassifier(),
				new PaletteEntropyClassifier()));
	}

	public ClassificationResult classify(ChunkObservation observation, BaselineStore baselines) {
		ClassifierContext context = new ClassifierContext(baselines, observation.server(), observation.dimension());
		Map<String, Double> signals = new LinkedHashMap<>();
		double weightedSum = 0;
		double weightTotal = 0;

		for (ChunkClassifier classifier : classifiers) {
			double signal = classifier.classify(observation, context);
			if (Double.isNaN(signal)) {
				continue;
			}
			double clamped = Math.max(-1.0, Math.min(1.0, signal));
			signals.put(classifier.id(), clamped);
			weightedSum += clamped * classifier.weight();
			weightTotal += classifier.weight();
		}

		if (weightTotal <= 0) {
			return new ClassificationResult(Classification.UNKNOWN, 0.5, signals);
		}

		double combined = weightedSum / weightTotal;
		double confidence = (combined + 1.0) / 2.0;
		Classification classification;
		if (confidence >= NEW_THRESHOLD) {
			classification = Classification.LIKELY_NEW;
		} else if (confidence <= EXISTING_THRESHOLD) {
			classification = Classification.LIKELY_EXISTING;
		} else {
			classification = Classification.UNKNOWN;
		}
		return new ClassificationResult(classification, confidence, signals);
	}
}

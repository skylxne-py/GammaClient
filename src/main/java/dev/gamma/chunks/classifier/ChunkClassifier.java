package dev.gamma.chunks.classifier;

import dev.gamma.chunks.model.ChunkObservation;

/**
 * One independent signal in the pluggable pipeline (roadmap: "never hardcode a single
 * technique — servers patch them and I want to swap strategies without surgery"). Pure and
 * Minecraft-free by design, so every implementation is unit-testable against a hand-built
 * {@link ChunkObservation} — no running game needed.
 */
public interface ChunkClassifier {

	String id();

	/** How much this classifier's vote counts in {@link ClassifierPipeline}'s weighted combination. */
	double weight();

	/**
	 * A vote in {@code [-1, 1]} — positive leans "freshly generated", negative leans "already
	 * explored". Return {@link Double#NaN} to abstain (e.g. a baseline that hasn't warmed up
	 * yet, or a metric that doesn't apply to this chunk at all).
	 */
	double classify(ChunkObservation observation, ClassifierContext context);
}

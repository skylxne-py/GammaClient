package dev.gamma.chunks.classifier;

import dev.gamma.chunks.model.ChunkObservation;

/**
 * Block-entity presence inconsistencies, narrowed to a real, verified one: a generated container
 * (chest, barrel, ...) that hasn't been opened yet stores an unresolved loot-table reference
 * instead of real items — real, documented vanilla behavior
 * ({@code RandomizableContainerBlockEntity#getLootTable()}, verified against the 26.2 jar), not
 * assumed. A chunk full of never-opened loot containers is a strong sign nobody has been here.
 * Self-normalizing (a ratio, not a baseline z-score) so it never needs calibration and abstains
 * cleanly on chunks with no containers at all rather than reporting a meaningless 0.
 */
public final class UnrolledLootClassifier implements ChunkClassifier {

	private final double weight;

	public UnrolledLootClassifier(double weight) {
		this.weight = weight;
	}

	public UnrolledLootClassifier() {
		this(1.0);
	}

	@Override
	public String id() {
		return "unrolled-loot";
	}

	@Override
	public double weight() {
		return weight;
	}

	@Override
	public double classify(ChunkObservation observation, ClassifierContext context) {
		if (observation.totalContainers() <= 0) {
			return Double.NaN;
		}
		double ratio = observation.unrolledLootContainers() / (double) observation.totalContainers();
		return ratio * 2.0 - 1.0;
	}
}

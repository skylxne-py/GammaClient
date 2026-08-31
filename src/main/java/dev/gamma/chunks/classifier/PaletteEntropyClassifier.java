package dev.gamma.chunks.classifier;

import dev.gamma.chunks.model.ChunkObservation;

/**
 * Palette entropy and packet size relative to a per-server-per-dimension baseline (the roadmap
 * pairs these two, and they're measured together here for the same reason: both are proxies for
 * "how much distinct terrain/structure detail is packed into this chunk," and world-gen-fresh
 * chunks in a biome tend to sit at a fairly consistent point on that scale that a revisit
 * (already-modified by a player, or just naturally varied) tends to drift away from). Averages
 * whichever of the two sub-signals has enough baseline data rather than requiring both.
 */
public final class PaletteEntropyClassifier implements ChunkClassifier {

	public static final String ENTROPY_METRIC = "palette_entropy";
	public static final String SIZE_METRIC = "packet_size_bytes";
	private static final double SATURATION_STDDEVS = 3.0;

	private final double weight;

	public PaletteEntropyClassifier(double weight) {
		this.weight = weight;
	}

	public PaletteEntropyClassifier() {
		this(0.8);
	}

	@Override
	public String id() {
		return "palette-entropy";
	}

	@Override
	public double weight() {
		return weight;
	}

	@Override
	public double classify(ChunkObservation observation, ClassifierContext context) {
		RollingBaseline entropyBaseline = context.baseline(ENTROPY_METRIC);
		RollingBaseline sizeBaseline = context.baseline(SIZE_METRIC);

		double entropyZ = entropyBaseline.zScore(observation.paletteEntropy());
		double sizeZ = sizeBaseline.zScore(observation.packetSizeBytes());
		entropyBaseline.update(observation.paletteEntropy());
		sizeBaseline.update(observation.packetSizeBytes());

		if (Double.isNaN(entropyZ) && Double.isNaN(sizeZ)) {
			return Double.NaN;
		}
		double sum = 0;
		int n = 0;
		if (!Double.isNaN(entropyZ)) {
			sum += entropyZ;
			n++;
		}
		if (!Double.isNaN(sizeZ)) {
			sum += sizeZ;
			n++;
		}
		return (sum / n) / SATURATION_STDDEVS;
	}
}

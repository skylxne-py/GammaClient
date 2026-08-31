package dev.gamma.chunks.classifier;

import dev.gamma.chunks.model.ChunkObservation;

/**
 * Flowing water/lava block updates arriving in the first moments after a chunk loads suggest
 * terrain that has never ticked before (a revisited chunk's fluids already settled long ago).
 * {@link dev.gamma.chunks.ChunkObservationCollector} counts these in a short post-load window;
 * this classifier just scores the count against a per-server-per-dimension baseline.
 */
public final class LiquidSettlingClassifier implements ChunkClassifier {

	public static final String METRIC = "fluid_update_count";
	private static final double SATURATION_STDDEVS = 3.0;

	private final double weight;

	public LiquidSettlingClassifier(double weight) {
		this.weight = weight;
	}

	public LiquidSettlingClassifier() {
		this(1.0);
	}

	@Override
	public String id() {
		return "liquid-settling";
	}

	@Override
	public double weight() {
		return weight;
	}

	@Override
	public double classify(ChunkObservation observation, ClassifierContext context) {
		RollingBaseline baseline = context.baseline(METRIC);
		double z = baseline.zScore(observation.fluidUpdateCount());
		baseline.update(observation.fluidUpdateCount());
		return Double.isNaN(z) ? Double.NaN : z / SATURATION_STDDEVS;
	}
}

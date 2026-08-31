package dev.gamma.chunks.classifier;

import dev.gamma.chunks.model.ChunkObservation;

/**
 * Time between a chunk position entering render distance and its packet arriving. Freshly
 * generated chunks measurably take longer server-side than chunks served from region files —
 * the primary candidate signal. Scored as a z-score against a per-server-per-dimension
 * {@link RollingBaseline}, which is where calibration and outlier rejection under lag
 * actually happen (see {@link RollingBaseline}).
 */
public final class GenerationLatencyClassifier implements ChunkClassifier {

	public static final String METRIC = "generation_latency_ms";
	private static final double SATURATION_STDDEVS = 3.0;

	private final double weight;

	public GenerationLatencyClassifier(double weight) {
		this.weight = weight;
	}

	public GenerationLatencyClassifier() {
		this(1.5);
	}

	@Override
	public String id() {
		return "generation-latency";
	}

	@Override
	public double weight() {
		return weight;
	}

	@Override
	public double classify(ChunkObservation observation, ClassifierContext context) {
		if (observation.requestLatencyMillis() < 0) {
			return Double.NaN;
		}
		RollingBaseline baseline = context.baseline(METRIC);
		double z = baseline.zScore(observation.requestLatencyMillis());
		baseline.update(observation.requestLatencyMillis());
		return Double.isNaN(z) ? Double.NaN : z / SATURATION_STDDEVS;
	}
}

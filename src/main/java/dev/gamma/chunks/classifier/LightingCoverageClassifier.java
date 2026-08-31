package dev.gamma.chunks.classifier;

import dev.gamma.chunks.model.ChunkObservation;

/**
 * Lighting-data characteristics on arrival — one of the roadmap's "do not assume it works,
 * empirically validate" candidates. Hypothesis: a freshly generated chunk gets its lighting
 * computed proactively by world-gen and arrives with light data for close to every section;
 * an already-explored chunk loaded from a region file can arrive with a sparser initial
 * light packet if propagation is still settling from neighbors. Scored as a z-score of the
 * populated sky+block light layer count ({@code ClientboundLightUpdatePacketData}'s
 * {@code skyYMask}/{@code blockYMask} cardinality) against a per-server-per-dimension baseline.
 * Weighted lower than the other classifiers until real-world accuracy is on record —
 * see {@code docs/CLASSIFIERS.md}.
 */
public final class LightingCoverageClassifier implements ChunkClassifier {

	public static final String METRIC = "light_coverage";
	private static final double SATURATION_STDDEVS = 3.0;

	private final double weight;

	public LightingCoverageClassifier(double weight) {
		this.weight = weight;
	}

	public LightingCoverageClassifier() {
		this(0.5);
	}

	@Override
	public String id() {
		return "lighting-coverage";
	}

	@Override
	public double weight() {
		return weight;
	}

	@Override
	public double classify(ChunkObservation observation, ClassifierContext context) {
		RollingBaseline baseline = context.baseline(METRIC);
		double z = baseline.zScore(observation.lightCoverageSignal());
		baseline.update(observation.lightCoverageSignal());
		return Double.isNaN(z) ? Double.NaN : z / SATURATION_STDDEVS;
	}
}

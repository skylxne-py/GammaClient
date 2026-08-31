package dev.gamma.chunks.classifier;

import dev.gamma.chunks.model.ChunkObservation;

/**
 * Stand-in for the block-update-packet-flags candidate signal. Verified against the
 * real 26.2 jar ({@code javap} on {@code ClientboundBlockUpdatePacket} /
 * {@code ClientboundSectionBlocksUpdatePacket}) that neither packet exposes per-block edit flags
 * on the wire in this version — that field existed in much older protocol versions and is gone
 * now, so the literal signal doesn't exist to read (see the design notes). What's still
 * real and observable on the same packet stream: the sheer volume of block-update packets a
 * chunk receives in the moments right after it loads. World-gen decoration passes (trees, caves,
 * fluid carving, structure post-processing) that finish just after generation produce a
 * measurable burst of corrections; an already-settled, previously-explored chunk mostly doesn't.
 * Scored the same way as {@link LiquidSettlingClassifier} — against a per-server-per-dimension
 * baseline of total (not just fluid) update count in the post-load window.
 */
public final class PostLoadUpdateBurstClassifier implements ChunkClassifier {

	public static final String METRIC = "post_load_update_count";
	private static final double SATURATION_STDDEVS = 3.0;

	private final double weight;

	public PostLoadUpdateBurstClassifier(double weight) {
		this.weight = weight;
	}

	public PostLoadUpdateBurstClassifier() {
		this(0.7);
	}

	@Override
	public String id() {
		return "post-load-update-burst";
	}

	@Override
	public double weight() {
		return weight;
	}

	@Override
	public double classify(ChunkObservation observation, ClassifierContext context) {
		RollingBaseline baseline = context.baseline(METRIC);
		double z = baseline.zScore(observation.totalUpdateCount());
		baseline.update(observation.totalUpdateCount());
		return Double.isNaN(z) ? Double.NaN : z / SATURATION_STDDEVS;
	}
}

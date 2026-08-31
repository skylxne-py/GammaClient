package dev.gamma.chunks.classifier;

/** Per-classify-call handle a {@link ChunkClassifier} uses to reach its calibration baselines. */
public record ClassifierContext(BaselineStore baselines, String server, String dimension) {

	public RollingBaseline baseline(String metric) {
		return baselines.get(server, dimension, metric);
	}
}

package dev.gamma.chunks.model;

/** Verdict a {@link dev.gamma.chunks.classifier.ClassifierPipeline} reaches for one chunk. */
public enum Classification {
	LIKELY_NEW,
	LIKELY_EXISTING,
	/** Not enough classifiers had an opinion (cold baselines, no containers, etc.) to call it either way. */
	UNKNOWN
}

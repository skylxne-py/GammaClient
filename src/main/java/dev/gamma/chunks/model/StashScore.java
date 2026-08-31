package dev.gamma.chunks.model;

/** One {@link ChunkRecord}'s stash-likelihood breakdown from {@code dev.gamma.chunks.StashScorer} — each component is normalized to {@code [0,1]} before weighting. */
public record StashScore(ChunkRecord chunk, double density, double clustering, double proximity, double total) {
}

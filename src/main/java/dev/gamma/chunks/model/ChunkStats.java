package dev.gamma.chunks.model;

/** Summary row for {@code .chunks stats} — see {@code dev.gamma.chunks.db.ChunkDatabase#stats}. */
public record ChunkStats(
		int totalChunks,
		int likelyNew,
		int likelyExisting,
		int unknown,
		long totalStorageBlocks,
		double averageConfidence
) {
	public static final ChunkStats EMPTY = new ChunkStats(0, 0, 0, 0, 0, 0);
}

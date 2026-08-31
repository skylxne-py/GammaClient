package dev.gamma.chunks.model;

/**
 * Filter criteria for {@code .chunks query} / {@code ChunkDatabase#query}. Any field left
 * {@code null} (or {@link Integer#MIN_VALUE}/{@link Integer#MAX_VALUE} for the open-ended bound
 * fields) is not applied.
 */
public record ChunkQuery(
		String dimension,
		Integer minX,
		Integer maxX,
		Integer minZ,
		Integer maxZ,
		Integer minStorageCount,
		Classification classification,
		Long maxAgeMillis,
		int limit
) {
	public static ChunkQuery all(int limit) {
		return new ChunkQuery(null, null, null, null, null, null, null, null, limit);
	}
}

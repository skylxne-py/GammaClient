package dev.gamma.chunks.model;

import java.util.Map;

/** One row of the chunk DB — see {@code dev.gamma.chunks.db.ChunkSchema}. */
public record ChunkRecord(
		String dimension,
		int x,
		int z,
		long firstSeen,
		long lastSeen,
		int revisitCount,
		Classification classification,
		double confidence,
		long contentHash,
		int storageCount,
		Map<String, Integer> blockEntityCounts,
		Map<String, Integer> notableBlockCounts
) {
}

package dev.gamma.chunks.model;

import java.util.Map;

/**
 * Everything gathered about one chunk load, in plain data — deliberately free of any
 * {@code net.minecraft} type so {@link dev.gamma.chunks.classifier.ChunkClassifier}
 * implementations (and their unit tests) never need a running game to execute.
 * {@link dev.gamma.chunks.ChunkObservationCollector} is the only place that builds one of
 * these from real packets/chunks.
 *
 * @param server                  profile key (server address, or "singleplayer")
 * @param dimension               e.g. {@code "minecraft:overworld"}
 * @param requestLatencyMillis    time between the chunk position entering render distance and
 *                                the chunk packet arriving; {@code -1} if never tracked (e.g.
 *                                the module was enabled after the chunk was already in flight)
 * @param packetSizeBytes         size of the raw {@code ClientboundLevelChunkPacketData} payload
 * @param lightCoverageSignal     populated sky+block light layer count from the light packet
 * @param paletteEntropy          normalized Shannon entropy (0..1) of block-state occurrence
 *                                across all sections — see {@code PaletteEntropy}
 * @param fluidUpdateCount        flowing-fluid block updates observed in the post-load window
 * @param totalUpdateCount        all block updates observed in the post-load window
 * @param unrolledLootContainers  containers whose loot table hasn't been rolled yet
 * @param totalContainers         containers of any kind found in the chunk
 * @param blockEntityCounts       per-block-entity-type counts (chest, barrel, ...)
 * @param notableBlockCounts      per-block counts from the configurable notable-block list
 * @param contentHash             cheap hash so a revisit can be diffed against the stored record
 */
public record ChunkObservation(
		String server,
		String dimension,
		int chunkX,
		int chunkZ,
		long loadTimeMillis,
		long requestLatencyMillis,
		int packetSizeBytes,
		int lightCoverageSignal,
		double paletteEntropy,
		int fluidUpdateCount,
		int totalUpdateCount,
		int unrolledLootContainers,
		int totalContainers,
		Map<String, Integer> blockEntityCounts,
		Map<String, Integer> notableBlockCounts,
		long contentHash
) {
	public int storageCount() {
		return blockEntityCounts.values().stream().mapToInt(Integer::intValue).sum();
	}
}

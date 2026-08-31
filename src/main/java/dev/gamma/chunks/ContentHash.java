package dev.gamma.chunks;

import java.util.Map;

/**
 * Cheap, order-independent content hash so a revisited chunk can be diffed against its stored
 * record without re-running the full classifier pipeline. Pure function — no Minecraft types —
 * so it's directly unit-testable.
 */
public final class ContentHash {

	private ContentHash() {
	}

	public static long compute(Map<String, Integer> blockEntityCounts, Map<String, Integer> notableBlockCounts,
			double paletteEntropy, int packetSizeBytes) {
		long hash = 1_125_899_906_842_597L;
		hash = 31 * hash + mixOrderIndependent(blockEntityCounts);
		hash = 31 * hash + mixOrderIndependent(notableBlockCounts);
		hash = 31 * hash + Double.hashCode(paletteEntropy);
		hash = 31 * hash + packetSizeBytes;
		return hash;
	}

	/** Sums per-entry hashes instead of folding in iteration order, so map key order never changes the result. */
	private static long mixOrderIndependent(Map<String, Integer> map) {
		long sum = 0;
		for (Map.Entry<String, Integer> entry : map.entrySet()) {
			sum += (entry.getKey().hashCode() * 31L) ^ entry.getValue();
		}
		return sum;
	}
}

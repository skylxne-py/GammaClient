package dev.gamma.chunks.model;

import java.util.Map;
import java.util.Set;

/**
 * Everything {@code StashScorer} needs to turn a pool of {@link ChunkRecord}s into scores.
 *
 * <p>A record rather than six positional parameters: three of them are unlabelled doubles in a row,
 * which is the shape a caller silently gets wrong. It also gives the filter somewhere to live.
 *
 * @param density         weight of raw counted-container density
 * @param clustering      weight of tier-weighted container variety
 * @param proximity       weight of nearby candidates' density
 * @param proximityRadius chunk radius treated as "nearby"
 * @param countedTypes    block-entity type names that count at all; anything outside this set is
 *                        ignored by both density and clustering
 */
public record StashWeights(double density, double clustering, double proximity, int proximityRadius, Set<String> countedTypes) {

	/** Every type the collector knows how to record. Not the default *selection* — see {@code StashFinder}. */
	public static final Set<String> ALL_TYPES = Set.of(
			"chest", "trapped_chest", "barrel", "shulker_box", "ender_chest",
			"hopper", "furnace", "dropper", "dispenser", "brewing_stand");

	/** Used when no {@code StashFinder} instance exists yet — mirrors that module's own defaults. */
	public static final StashWeights DEFAULTS = new StashWeights(0.5, 0.3, 0.2, 3,
			Set.of("chest", "trapped_chest", "barrel", "shulker_box", "hopper"));

	/**
	 * Storage count restricted to the counted types.
	 *
	 * <p>{@link ChunkRecord#storageCount()} is the total the chunk was <em>logged</em> with, across
	 * every type, and stays that way — the DB is a long-term record and excluding a type from
	 * scoring must not destroy the ability to include it again later. Filtering happens here, at
	 * scoring time, so toggling a type re-scores history instead of only affecting new chunks.
	 */
	public int countedStorage(ChunkRecord chunk) {
		return countedStorage(chunk.blockEntityCounts());
	}

	/** Same filter against a raw per-type count map, for a {@link dev.gamma.chunks.model.ChunkObservation} that has no {@link ChunkRecord} yet. */
	public int countedStorage(Map<String, Integer> blockEntityCounts) {
		int total = 0;
		for (var entry : blockEntityCounts.entrySet()) {
			if (countedTypes.contains(entry.getKey())) {
				total += entry.getValue();
			}
		}
		return total;
	}
}

package dev.gamma.chunks;

import dev.gamma.chunks.model.ChunkRecord;
import dev.gamma.chunks.model.StashScore;
import dev.gamma.chunks.model.StashWeights;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Roadmap: "Score each chunk on storage-block density, unusual block-entity clustering, and
 * proximity to other high-scoring chunks." All three signals are computed over a candidate pool
 * the caller already fetched (typically the top-N-by-{@code storage_count} rows from {@code
 * ChunkDatabase#query}) rather than this class issuing its own per-chunk neighbor queries —
 * proximity is approximated as "how storage-dense are the *other* chunks in this same pool that
 * happen to be nearby", which is honest about only seeing candidates that already cleared the
 * density bar, not a true full-DB radius search. Documented as a deliberate scope trade in
 * the design notes (Phase 6): a real neighbor search would mean one DB round trip per
 * candidate, which doesn't fit "surface the top N" as a single cheap command.
 *
 * <p>Which container types count at all is {@link StashWeights#countedTypes}, applied here rather
 * than in {@code ChunkObservationCollector}: the collector's job is to record what was there, and a
 * type excluded at logging time would be unrecoverable. Excluded here, the same stored rows re-score
 * the moment a toggle flips.
 */
public final class StashScorer {

	private StashScorer() {
	}

	public static List<StashScore> score(List<ChunkRecord> candidates, StashWeights weights) {
		int maxStorage = candidates.stream().mapToInt(weights::countedStorage).max().orElse(0);
		List<StashScore> scores = new ArrayList<>(candidates.size());
		for (ChunkRecord chunk : candidates) {
			double density = maxStorage == 0 ? 0.0 : weights.countedStorage(chunk) / (double) maxStorage;
			double clustering = clusteringScore(chunk, weights);
			double proximity = proximityScore(chunk, candidates, weights, maxStorage);
			double total = density * weights.density() + clustering * weights.clustering() + proximity * weights.proximity();
			scores.add(new StashScore(chunk, density, clustering, proximity, total));
		}
		scores.sort(Comparator.comparingDouble(StashScore::total).reversed());
		return scores;
	}

	/** A single score for one already-known chunk, reusing the same weighting — used by {@code StashFinder}'s live auto-waypoint check against a small neighbor pool it queried itself. */
	public static double scoreOne(ChunkRecord target, List<ChunkRecord> neighborPool, StashWeights weights) {
		for (StashScore s : score(neighborPool, weights)) {
			if (s.chunk().dimension().equals(target.dimension()) && s.chunk().x() == target.x() && s.chunk().z() == target.z()) {
				return s.total();
			}
		}
		return 0.0;
	}

	/** Rarer containers (shulkers, ender chests) count for more than ubiquitous ones (hoppers, furnaces); a chunk with several types is scored higher than one with a pile of a single common type. */
	private static double clusteringScore(ChunkRecord chunk, StashWeights weights) {
		double score = 0.0;
		for (Map.Entry<String, Integer> entry : chunk.blockEntityCounts().entrySet()) {
			if (!weights.countedTypes().contains(entry.getKey())) {
				continue;
			}
			double weight = switch (entry.getKey()) {
				case "shulker_box", "ender_chest" -> 3.0;
				case "chest", "trapped_chest", "barrel" -> 1.5;
				default -> 0.5;
			};
			score += weight * entry.getValue();
		}
		return Math.min(1.0, score / 20.0);
	}

	private static double proximityScore(ChunkRecord chunk, List<ChunkRecord> pool, StashWeights weights, int maxStorage) {
		if (maxStorage == 0) {
			return 0.0;
		}
		double sum = 0.0;
		int count = 0;
		for (ChunkRecord other : pool) {
			if (other == chunk || !other.dimension().equals(chunk.dimension())) {
				continue;
			}
			if (Math.abs(other.x() - chunk.x()) <= weights.proximityRadius() && Math.abs(other.z() - chunk.z()) <= weights.proximityRadius()) {
				sum += weights.countedStorage(other) / (double) maxStorage;
				count++;
			}
		}
		return count == 0 ? 0.0 : Math.min(1.0, sum / count);
	}
}

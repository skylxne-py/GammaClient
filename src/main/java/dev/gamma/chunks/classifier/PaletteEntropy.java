package dev.gamma.chunks.classifier;

/**
 * Pure math backing {@link PaletteEntropyClassifier}'s input — kept separate from anything
 * Minecraft-shaped so it can be unit tested directly. {@link dev.gamma.chunks.ChunkObservationCollector}
 * feeds it per-section block-state occurrence counts from {@code PalettedContainer.count(...)}.
 */
public final class PaletteEntropy {

	private PaletteEntropy() {
	}

	/**
	 * Shannon entropy of the given occurrence counts, normalized to {@code [0,1]} by the maximum
	 * possible entropy for that many distinct values (0 = one state dominates everything, 1 =
	 * every distinct state is equally common). Zero/negative counts are ignored; an input with
	 * fewer than two distinct positive counts always normalizes to 0.
	 */
	public static double normalizedShannonEntropy(int[] counts) {
		long total = 0;
		int distinct = 0;
		for (int count : counts) {
			if (count > 0) {
				total += count;
				distinct++;
			}
		}
		if (total <= 0 || distinct <= 1) {
			return 0.0;
		}
		double entropy = 0.0;
		for (int count : counts) {
			if (count <= 0) {
				continue;
			}
			double p = count / (double) total;
			entropy -= p * (Math.log(p) / Math.log(2));
		}
		double maxEntropy = Math.log(distinct) / Math.log(2);
		return maxEntropy <= 0 ? 0.0 : entropy / maxEntropy;
	}
}

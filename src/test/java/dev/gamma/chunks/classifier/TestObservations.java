package dev.gamma.chunks.classifier;

import dev.gamma.chunks.model.ChunkObservation;

import java.util.Map;

/** Small builder so classifier tests don't each spell out {@link ChunkObservation}'s full 15-arg constructor. */
final class TestObservations {

	private TestObservations() {
	}

	static Builder builder() {
		return new Builder();
	}

	static final class Builder {
		private String server = "test-server";
		private String dimension = "minecraft:overworld";
		private int chunkX;
		private int chunkZ;
		private long loadTimeMillis = 0;
		private long requestLatencyMillis = -1;
		private int packetSizeBytes = 10_000;
		private int lightCoverageSignal = 40;
		private double paletteEntropy = 0.3;
		private int fluidUpdateCount = 0;
		private int totalUpdateCount = 0;
		private int unrolledLootContainers = 0;
		private int totalContainers = 0;
		private Map<String, Integer> blockEntityCounts = Map.of();
		private Map<String, Integer> notableBlockCounts = Map.of();

		Builder requestLatencyMillis(long value) {
			this.requestLatencyMillis = value;
			return this;
		}

		Builder packetSizeBytes(int value) {
			this.packetSizeBytes = value;
			return this;
		}

		Builder lightCoverageSignal(int value) {
			this.lightCoverageSignal = value;
			return this;
		}

		Builder paletteEntropy(double value) {
			this.paletteEntropy = value;
			return this;
		}

		Builder fluidUpdateCount(int value) {
			this.fluidUpdateCount = value;
			return this;
		}

		Builder totalUpdateCount(int value) {
			this.totalUpdateCount = value;
			return this;
		}

		Builder unrolledLootContainers(int value) {
			this.unrolledLootContainers = value;
			return this;
		}

		Builder totalContainers(int value) {
			this.totalContainers = value;
			return this;
		}

		ChunkObservation build() {
			return new ChunkObservation(server, dimension, chunkX, chunkZ, loadTimeMillis, requestLatencyMillis,
					packetSizeBytes, lightCoverageSignal, paletteEntropy, fluidUpdateCount, totalUpdateCount,
					unrolledLootContainers, totalContainers, blockEntityCounts, notableBlockCounts, 0);
		}
	}
}

package dev.gamma.chunks.classifier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaletteEntropyTest {

	@Test
	void oneDominantStateIsZeroEntropy() {
		assertEquals(0.0, PaletteEntropy.normalizedShannonEntropy(new int[] {4096}), 1e-9);
	}

	@Test
	void emptyIsZeroEntropy() {
		assertEquals(0.0, PaletteEntropy.normalizedShannonEntropy(new int[0]), 1e-9);
		assertEquals(0.0, PaletteEntropy.normalizedShannonEntropy(new int[] {0, 0, 0}), 1e-9);
	}

	@Test
	void perfectlyEvenDistributionIsMaximalEntropy() {
		assertEquals(1.0, PaletteEntropy.normalizedShannonEntropy(new int[] {100, 100, 100, 100}), 1e-9);
	}

	@Test
	void skewedDistributionSitsBetweenZeroAndOne() {
		double entropy = PaletteEntropy.normalizedShannonEntropy(new int[] {1000, 10, 5, 1});
		assertTrue(entropy > 0.0 && entropy < 1.0, "expected a mid-range entropy, was " + entropy);
	}

	@Test
	void negativeAndZeroCountsAreIgnored() {
		double withNoise = PaletteEntropy.normalizedShannonEntropy(new int[] {50, 50, 0, -3});
		double clean = PaletteEntropy.normalizedShannonEntropy(new int[] {50, 50});
		assertEquals(clean, withNoise, 1e-9);
	}
}

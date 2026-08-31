package dev.gamma.chunks.classifier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollingBaselineTest {

	@Test
	void abstainsBeforeMinimumSamples() {
		RollingBaseline baseline = new RollingBaseline();
		for (int i = 0; i < 7; i++) {
			assertTrue(Double.isNaN(baseline.zScore(100)), "should abstain before warm-up");
			baseline.update(100);
		}
	}

	@Test
	void scoresAgainstBaselineBeforeSampleIsFolded() {
		RollingBaseline baseline = new RollingBaseline();
		for (int i = 0; i < 20; i++) {
			baseline.update(100);
		}
		// stddev is 0 after only identical samples — zScore should report 0, not NaN or infinity.
		assertEquals(0.0, baseline.zScore(100), 1e-9);
		assertEquals(0.0, baseline.zScore(500), 1e-9);
	}

	@Test
	void detectsASlowSampleAsPositiveZScore() {
		RollingBaseline baseline = new RollingBaseline();
		double[] samples = {90, 100, 110, 95, 105, 100, 98, 102, 101, 99};
		for (double sample : samples) {
			baseline.update(sample);
		}
		double z = baseline.zScore(400);
		assertTrue(z > 3, "a sample far above a tight baseline should score a large positive z, was " + z);
	}

	@Test
	void rejectsExtremeOutliersFromCalibrationButStillScoresThem() {
		RollingBaseline baseline = new RollingBaseline();
		for (int i = 0; i < 20; i++) {
			baseline.update(100 + (i % 3));
		}
		double meanBefore = baseline.mean();
		double z = baseline.zScore(1_000_000);
		assertTrue(Double.isFinite(z) && z > 0, "an extreme sample should still be scorable");
		baseline.update(1_000_000);
		assertEquals(meanBefore, baseline.mean(), 1.0, "an extreme outlier should not meaningfully shift the calibrated mean");
	}

	@Test
	void countTracksUpdatesNotRejectedSamples() {
		RollingBaseline baseline = new RollingBaseline();
		for (int i = 0; i < 10; i++) {
			baseline.update(100);
		}
		assertEquals(10, baseline.count());
		baseline.update(1_000_000);
		assertEquals(10, baseline.count(), "an outlier beyond the rejection threshold should not be folded into calibration");
	}
}

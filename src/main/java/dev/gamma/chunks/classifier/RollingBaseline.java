package dev.gamma.chunks.classifier;

/**
 * Online mean/variance (Welford's algorithm) for one metric, calibrated per server as samples
 * arrive — per-server baseline calibration and outlier rejection under lag for
 * the generation-latency signal, reused by every other baseline-driven classifier too.
 *
 * <p>Scoring ({@link #zScore}) and calibration ({@link #update}) are deliberately separate calls
 * so a classifier can score a sample against the baseline as it stood *before* that sample, then
 * fold the sample in — otherwise every sample would be partly measured against itself.
 */
public final class RollingBaseline {

	/** Below this many samples, {@link #zScore} abstains (NaN) rather than reporting on a noisy baseline. */
	private static final int MIN_SAMPLES_FOR_SIGNAL = 8;

	/** A sample this many standard deviations out (e.g. a lag spike) still gets scored, but is not folded into the baseline. */
	private static final double OUTLIER_REJECTION_STDDEVS = 5.0;

	private long count;
	private double mean;
	private double m2;

	public synchronized void update(double sample) {
		if (count >= MIN_SAMPLES_FOR_SIGNAL && Math.abs(sample - mean) > OUTLIER_REJECTION_STDDEVS * stddevUnlocked()) {
			return;
		}
		count++;
		double delta = sample - mean;
		mean += delta / count;
		double delta2 = sample - mean;
		m2 += delta * delta2;
	}

	/** How many standard deviations {@code sample} sits from the current mean, or NaN before {@link #MIN_SAMPLES_FOR_SIGNAL} samples. */
	public synchronized double zScore(double sample) {
		if (count < MIN_SAMPLES_FOR_SIGNAL) {
			return Double.NaN;
		}
		double stddev = stddevUnlocked();
		if (stddev < 1e-9) {
			return 0.0;
		}
		return (sample - mean) / stddev;
	}

	public synchronized double mean() {
		return mean;
	}

	public synchronized double stddev() {
		return stddevUnlocked();
	}

	public synchronized long count() {
		return count;
	}

	private double stddevUnlocked() {
		return count < 2 ? 0.0 : Math.sqrt(m2 / (count - 1));
	}
}

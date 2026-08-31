package dev.gamma.gui.clickgui.anim;

/**
 * A float that eases toward a target over a fixed duration, driven by wall-clock time rather
 * than frame count so it plays back the same regardless of FPS. Every animated GUI/HUD property
 * (panel height, row expansion, toggle thumb, hover alpha, ...) is one of these — per project convention,
 * "nothing snaps."
 */
public final class Animated {

	private final Easing easing;
	private final long durationMillis;

	private double from;
	private double target;
	private double current;
	private long startedAtMillis;

	public Animated(double initial, Easing easing, long durationMillis) {
		this.easing = easing;
		this.durationMillis = durationMillis;
		this.from = initial;
		this.target = initial;
		this.current = initial;
		this.startedAtMillis = 0;
	}

	/** The house animation: a long, decelerating settle rather than a snap. */
	public static Animated of(double initial) {
		return new Animated(initial, Easing.EXPO_OUT, 240);
	}

	/** Retargets, keeping the current (in-flight) value as the new start point — no jump on redirect. */
	public void set(double target) {
		if (this.target == target) {
			return;
		}
		this.from = current;
		this.target = target;
		this.startedAtMillis = System.currentTimeMillis();
	}

	/** Sets the value immediately, cancelling any in-flight animation — for GUI open/close resets. */
	public void snapTo(double value) {
		this.from = value;
		this.target = value;
		this.current = value;
		this.startedAtMillis = 0;
	}

	/** Re-evaluates against wall-clock time and returns the current value. Call once per frame. */
	public double get() {
		if (current == target) {
			return current;
		}
		long elapsed = System.currentTimeMillis() - startedAtMillis;
		double t = durationMillis <= 0 ? 1.0 : Math.min(1.0, elapsed / (double) durationMillis);
		current = from + (target - from) * easing.apply(t);
		if (t >= 1.0) {
			current = target;
		}
		return current;
	}

	public double target() {
		return target;
	}

	public boolean isAnimating() {
		return current != target;
	}
}

package dev.gamma.modules.render;

import dev.gamma.config.setting.DoubleSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;

/**
 * Held-item position/rotation/scale and swing speed. All applied as an extra {@code PoseStack}
 * transform layered underneath vanilla's own — see
 * {@link dev.gamma.mixin.render.ItemInHandRendererMixin}, since there's no Fabric API seam into
 * first-person hand rendering.
 *
 * <p>{@code SwingSpeed} used to just rescale vanilla's own {@code attackAnim} value each frame --
 * but vanilla's {@code attackTime} (what {@code attackAnim} is derived from) resets on every new
 * swing regardless of our rescaling, so mining/attacking faster than one (slowed-down) animation's
 * duration cut it off and restarted it every hit instead of letting it finish. {@link
 * #advanceSwing} now drives its own independent timeline: it only starts a new cycle once the
 * previous one has actually finished, using vanilla's raw value purely as a "a swing was
 * requested" signal (there's no real event for that) rather than reading its progress directly --
 * so the animation always plays out in full, and triggers that arrive mid-animation are dropped
 * rather than queued, so nothing keeps animating after the real swinging stops.
 */
public final class ViewModel extends Module {

	private static final long BASE_SWING_DURATION_NANOS = 300_000_000L;

	public static volatile ViewModel instance;

	private final DoubleSetting offsetX = register(new DoubleSetting("OffsetX", "Held-item X offset.", 0.0, -2.0, 2.0));
	private final DoubleSetting offsetY = register(new DoubleSetting("OffsetY", "Held-item Y offset.", 0.0, -2.0, 2.0));
	private final DoubleSetting offsetZ = register(new DoubleSetting("OffsetZ", "Held-item Z offset.", 0.0, -2.0, 2.0));
	private final DoubleSetting rotationX = register(new DoubleSetting("RotationX", "Extra pitch, in degrees.", 0.0, -180.0, 180.0));
	private final DoubleSetting rotationY = register(new DoubleSetting("RotationY", "Extra yaw, in degrees.", 0.0, -180.0, 180.0));
	private final DoubleSetting rotationZ = register(new DoubleSetting("RotationZ", "Extra roll, in degrees.", 0.0, -180.0, 180.0));
	private final DoubleSetting scale = register(new DoubleSetting("Scale", "Held-item scale multiplier.", 1.0, 0.1, 3.0));
	private final DoubleSetting swingSpeed = register(new DoubleSetting("SwingSpeed", "Multiplies the swing animation's speed -- lower is slower, higher reaches full extension sooner.", 1.0, 0.1, 4.0));

	private float lastRawAttackAnim;
	private long swingStartNanos = -1;
	private boolean hasActiveSwing;

	public ViewModel() {
		super("ViewModel", "Repositions, rotates and rescales your held item.", Category.RENDER);
		instance = this;
	}

	public double offsetX() {
		return offsetX.get();
	}

	public double offsetY() {
		return offsetY.get();
	}

	public double offsetZ() {
		return offsetZ.get();
	}

	public double rotationX() {
		return rotationX.get();
	}

	public double rotationY() {
		return rotationY.get();
	}

	public double rotationZ() {
		return rotationZ.get();
	}

	public double scale() {
		return scale.get();
	}

	/**
	 * Called once per frame from the mixin with vanilla's own raw, unmodified {@code attackAnim}
	 * -- returns the progress value ({@code [0, 1]}) to actually render the swing pose with.
	 */
	public float advanceSwing(float rawAttackAnim) {
		long now = System.nanoTime();
		long duration = (long) (BASE_SWING_DURATION_NANOS / Math.max(0.01, swingSpeed.get()));
		boolean previousCycleFinished = !hasActiveSwing || (now - swingStartNanos) >= duration;

		// Vanilla resets attackTime (a sudden drop in attackAnim) whenever a new swing fires --
		// the only signal available for "a swing was requested" without a real event for it. A
		// rise from idle (0) covers the very first swing; a drop covers every swing after that.
		// Either way, only start a new cycle if ours already finished -- otherwise the trigger
		// is dropped, not queued, so the current animation always plays out fully.
		boolean risingFromIdle = rawAttackAnim > 0.001f && lastRawAttackAnim <= 0.001f;
		boolean droppedMidSwing = rawAttackAnim < lastRawAttackAnim - 0.01f;
		if ((risingFromIdle || droppedMidSwing) && previousCycleFinished) {
			swingStartNanos = now;
			hasActiveSwing = true;
		}
		lastRawAttackAnim = rawAttackAnim;

		if (!hasActiveSwing) {
			return 0f;
		}
		return Math.min(1.0f, (now - swingStartNanos) / (float) duration);
	}
}

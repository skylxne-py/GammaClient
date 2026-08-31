package dev.gamma.modules.render;

import dev.gamma.config.setting.BoolSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.config.setting.KeybindSetting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

/**
 * Hold the bind to look around without turning your actual body/aim — mouse movement is
 * captured and applied only to the render camera while held (see
 * {@link dev.gamma.mixin.render.MouseHandlerMixin}'s {@code turnPlayer} inject and
 * {@link dev.gamma.mixin.render.CameraMixin}), and resets the moment it's released.
 *
 * <p>Also swings the camera out to third person for the duration and puts it back on release, the
 * same save/restore {@link Freecam} does: freelook in first person is a camera that turns without
 * anything on screen turning with it, which reads as the world sliding rather than as looking
 * around. The previous camera type is only remembered when this module is the one that changed it,
 * so holding the bind during Freecam (which has already gone third person) doesn't leave two
 * modules fighting over the restore.
 */
public final class Freelook extends Module {

	public static volatile Freelook instance;

	private final BoolSetting thirdPerson = register(new BoolSetting("ThirdPerson", "Switch to third person while held, and back to the previous view on release.", true));

	private float yawOffset;
	private float pitchOffset;
	/** Non-null only while this module owns the camera-type change — see the class doc. */
	private CameraType previousCameraType;

	public Freelook() {
		super("Freelook", "Look around freely without turning your body.", Category.RENDER, KeybindSetting.Bind.unbound(KeybindSetting.Mode.HOLD));
		instance = this;
	}

	/** Held constantly and momentary by nature — announcing it would be two chat lines per glance. */
	@Override
	public boolean announcesToggle() {
		return false;
	}

	@Override
	protected void onEnable() {
		yawOffset = 0;
		pitchOffset = 0;
		if (!thirdPerson.get()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		CameraType current = client.options.getCameraType();
		if (current == CameraType.THIRD_PERSON_BACK) {
			return;
		}
		previousCameraType = current;
		client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
	}

	@Override
	protected void onDisable() {
		yawOffset = 0;
		pitchOffset = 0;
		if (previousCameraType != null) {
			Minecraft.getInstance().options.setCameraType(previousCameraType);
			previousCameraType = null;
		}
	}

	public float yawOffset() {
		return yawOffset;
	}

	public float pitchOffset() {
		return pitchOffset;
	}

	/** Fed raw look deltas from {@link dev.gamma.mixin.render.MouseHandlerMixin} while held. */
	public void look(double dYaw, double dPitch) {
		yawOffset = (float) (yawOffset + dYaw);
		pitchOffset = (float) Math.max(-90.0, Math.min(90.0, pitchOffset + dPitch));
	}
}

package dev.gamma.mixin.render;

import dev.gamma.modules.render.Freecam;
import dev.gamma.modules.render.Freelook;
import dev.gamma.modules.render.Zoom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Scroll delta has no Fabric API event and, unlike key state, can't be polled once per tick —
 * it's a one-shot offset per callback. {@code Zoom} needs it to adjust FOV while held, and
 * consumes (cancels) the scroll so vanilla doesn't also cycle the hotbar underneath it.
 *
 * <p>{@code turnPlayer} is similarly the only place raw look deltas exist before vanilla folds
 * them into the player's actual rotation. {@code Freelook}/{@code Freecam} need those deltas
 * without the player turning, so this cancels the vanilla turn and re-derives an equivalent
 * yaw/pitch delta from the same accumulated mouse motion instead.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

	@Shadow
	private double accumulatedDX;

	@Shadow
	private double accumulatedDY;

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void gamma$onScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
		Zoom zoom = Zoom.instance;
		if (zoom != null && zoom.adjustOnScroll(yOffset)) {
			ci.cancel();
		}
	}

	@Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	private void gamma$turnPlayer(double partialTick, CallbackInfo ci) {
		Freelook freelook = Freelook.instance;
		Freecam freecam = Freecam.instance;
		boolean freelookActive = freelook != null && freelook.isEnabled();
		boolean freecamActive = freecam != null && freecam.isEnabled();
		if (!freelookActive && !freecamActive) {
			return;
		}

		double sensitivity = Minecraft.getInstance().options.sensitivity().get() * 0.6 + 0.2;
		double factor = sensitivity * sensitivity * sensitivity * 8.0 * 0.15;
		double dYaw = accumulatedDX * factor;
		double dPitch = accumulatedDY * factor;

		if (freelookActive) {
			freelook.look(dYaw, dPitch);
		}
		if (freecamActive) {
			freecam.look(dYaw, dPitch);
		}
		accumulatedDX = 0;
		accumulatedDY = 0;
		ci.cancel();
	}
}

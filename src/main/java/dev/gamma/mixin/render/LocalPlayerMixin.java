package dev.gamma.mixin.render;

import dev.gamma.modules.render.Freecam;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freecam only ever overrides the render {@code Camera} (see {@link
 * dev.gamma.mixin.render.CameraMixin}) -- the real player entity was still fully driven by the
 * same WASD/jump keys Freecam reads, since vanilla's own input handling has no idea Freecam
 * exists. That moved the real player alongside the camera, leaving it in a different spot once
 * Freecam was disabled. Zeroing the movement-derived fields and skipping the rest of
 * {@code applyInput()} (bobbing, etc.) while Freecam is active keeps the real player exactly
 * where it was, the same way vanilla spectator mode decouples camera movement from the entity
 * it's following. This only touches movement input -- attack/use/interact are handled entirely
 * separately in vanilla, so eating and breaking blocks are unaffected.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {

	@Inject(method = "applyInput", at = @At("HEAD"), cancellable = true)
	private void gamma$suppressInputDuringFreecam(CallbackInfo ci) {
		Freecam freecam = Freecam.instance;
		if (freecam == null || !freecam.isEnabled()) {
			return;
		}
		LocalPlayer self = (LocalPlayer) (Object) this;
		self.xxa = 0;
		self.zza = 0;
		self.setJumping(false);
		ci.cancel();
	}
}

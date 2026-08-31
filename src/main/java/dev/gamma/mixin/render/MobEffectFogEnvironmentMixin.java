package dev.gamma.mixin.render;

import dev.gamma.modules.render.NoRender;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.BlindnessFogEnvironment;
import net.minecraft.client.renderer.fog.environment.DarknessFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code NoRender}'s darkness/blindness toggles. Both effects are implemented as
 * {@code MobEffectFogEnvironment} subclasses with an identical {@code setupFog} signature, so
 * one mixin covers both targets — which one applies is resolved at runtime by instance type.
 */
@Mixin({DarknessFogEnvironment.class, BlindnessFogEnvironment.class})
public abstract class MobEffectFogEnvironmentMixin {

	@Inject(method = "setupFog", at = @At("HEAD"), cancellable = true)
	private void gamma$setupFog(FogData data, Camera camera, ClientLevel level, float partialTick, DeltaTracker deltaTracker, CallbackInfo ci) {
		NoRender noRender = NoRender.instance;
		if (noRender == null) {
			return;
		}
		boolean isDarkness = (Object) this instanceof DarknessFogEnvironment;
		boolean enabled = isDarkness ? noRender.darknessEnabled() : noRender.blindnessEnabled();
		if (!enabled) {
			ci.cancel();
		}
	}
}

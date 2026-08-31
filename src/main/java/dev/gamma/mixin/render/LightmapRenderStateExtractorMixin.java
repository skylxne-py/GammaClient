package dev.gamma.mixin.render;

import dev.gamma.modules.render.Fullbright;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code Fullbright}'s night-vision mode. {@code LightmapRenderState.nightVisionEffectIntensity}
 * is exactly the boost vanilla applies for the real Night Vision potion effect — the client
 * can observe whether the player has that effect but can't grant it, so this fakes the render
 * side of it directly rather than trying to reproduce the lightmap math from scratch.
 */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapRenderStateExtractorMixin {

	@Inject(method = "extract", at = @At("RETURN"))
	private void gamma$forceNightVision(LightmapRenderState state, float partialTick, CallbackInfo ci) {
		Fullbright fullbright = Fullbright.instance;
		if (fullbright != null && fullbright.nightVisionActive()) {
			state.nightVisionEffectIntensity = 1.0f;
			state.darknessEffectScale = 0.0f;
		}
	}
}

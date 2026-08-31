package dev.gamma.mixin.render;

import dev.gamma.modules.render.Ambience;
import dev.gamma.util.ColorUtil;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code Ambience}'s fog color/density override. {@code FogData} is a plain mutable-field
 * record-like object returned from {@code setupFog}, so overriding it after vanilla computes
 * its normal value is a single clean return-value mutation — no need to reimplement the fog
 * math vanilla already did.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {

	@Inject(method = "setupFog", at = @At("RETURN"))
	private void gamma$overrideFog(Camera camera, int renderDistance, DeltaTracker deltaTracker, float partialTick, ClientLevel level, CallbackInfoReturnable<FogData> cir) {
		FogData data = cir.getReturnValue();

		Ambience ambience = Ambience.instance;
		if (ambience != null && ambience.overridesFogColor()) {
			int argb = ambience.fogColorArgb();
			data.color = new Vector4f(ColorUtil.red(argb) / 255f, ColorUtil.green(argb) / 255f, ColorUtil.blue(argb) / 255f, 1.0f);
		}

		// Unconditional, and deliberately not gated on Ambience being enabled: thin fog is the
		// default for this client and Ambience is only the opt-out. Ambience.densityFactor() owns
		// that decision, including the null-instance case during early startup, so this stays a
		// plain multiply with no policy in it.
		float scale = (float) (1.0 / Ambience.densityFactor());
		data.environmentalEnd *= scale;
		data.renderDistanceEnd *= scale;
		data.cloudEnd *= scale;
	}
}

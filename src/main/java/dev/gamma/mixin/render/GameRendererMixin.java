package dev.gamma.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.gamma.modules.render.NoRender;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code NoRender}'s hurt-cam and view-bobbing toggles — both are private {@code PoseStack}-mutating methods, cancelled at the head. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	private void gamma$bobHurt(CameraRenderState state, PoseStack poseStack, CallbackInfo ci) {
		NoRender noRender = NoRender.instance;
		if (noRender != null && !noRender.hurtCamEnabled()) {
			ci.cancel();
		}
	}

	@Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
	private void gamma$bobView(CameraRenderState state, PoseStack poseStack, CallbackInfo ci) {
		NoRender noRender = NoRender.instance;
		if (noRender != null && !noRender.viewBobbingEnabled()) {
			ci.cancel();
		}
	}
}

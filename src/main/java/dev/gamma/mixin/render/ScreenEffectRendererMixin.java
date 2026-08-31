package dev.gamma.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.gamma.modules.render.NoRender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code NoRender}'s fire/water/block/pumpkin overlay toggles. Fire and water each go through
 * their own dedicated private static submit method, so those are simple head-cancels.
 * Block-in-face and pumpkin-in-face share one code path ({@code getViewBlockingState}, whose
 * result feeds {@code submitBlockSprite}) — this overrides its return to air, generically for
 * "block overlay" or specifically only when the block is a carved pumpkin for "pumpkin overlay".
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {

	@Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
	private static void gamma$submitFire(PoseStack poseStack, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
		NoRender noRender = NoRender.instance;
		if (noRender != null && !noRender.fireOverlayEnabled()) {
			ci.cancel();
		}
	}

	@Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
	private static void gamma$submitWater(Minecraft minecraft, PoseStack poseStack, SubmitNodeCollector collector, CallbackInfo ci) {
		NoRender noRender = NoRender.instance;
		if (noRender != null && !noRender.waterOverlayEnabled()) {
			ci.cancel();
		}
	}

	@Inject(method = "getViewBlockingState", at = @At("RETURN"), cancellable = true)
	private static void gamma$getViewBlockingState(Player player, CallbackInfoReturnable<BlockState> cir) {
		NoRender noRender = NoRender.instance;
		if (noRender == null) {
			return;
		}
		BlockState state = cir.getReturnValue();
		if (state == null) {
			return;
		}
		boolean isPumpkin = state.getBlock() == Blocks.CARVED_PUMPKIN;
		if (!noRender.blockOverlayEnabled() || (isPumpkin && !noRender.pumpkinOverlayEnabled())) {
			cir.setReturnValue(Blocks.AIR.defaultBlockState());
		}
	}
}

package dev.gamma.mixin.render;

import dev.gamma.modules.render.Xray;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code RenderSectionRegion} is the {@code BlockAndTintGetter} view of the world section
 * meshing sees — swapping a block out here (rather than in the real {@code ClientLevel}) means
 * Xray only ever affects what gets rendered, never actual world state, block interactions, or
 * collision.
 */
@Mixin(RenderSectionRegion.class)
public abstract class RenderSectionRegionMixin {

	@Inject(method = "getBlockState", at = @At("RETURN"), cancellable = true)
	private void gamma$xray(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		Xray xray = Xray.instance;
		if (xray == null) {
			return;
		}
		BlockState original = cir.getReturnValue();
		Block replacement = xray.replacementFor(original.getBlock());
		if (replacement != null) {
			cir.setReturnValue(replacement.defaultBlockState());
		}
	}
}

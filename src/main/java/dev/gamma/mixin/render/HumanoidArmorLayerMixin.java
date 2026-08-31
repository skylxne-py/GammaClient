package dev.gamma.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.gamma.modules.render.NoRender;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code NoRender}'s armor toggle. {@code renderArmorPiece} is the one place each individual
 * piece gets added to the submit collector — unlike the two overloaded {@code submit} methods
 * (a generic one plus its type-erasure bridge), its name isn't ambiguous, so it's the safer,
 * more precise mixin target.
 */
@Mixin(HumanoidArmorLayer.class)
public abstract class HumanoidArmorLayerMixin {

	@Inject(method = "renderArmorPiece", at = @At("HEAD"), cancellable = true)
	private void gamma$renderArmorPiece(PoseStack poseStack, SubmitNodeCollector collector, ItemStack stack, EquipmentSlot slot, int packedLight, HumanoidRenderState state, CallbackInfo ci) {
		NoRender noRender = NoRender.instance;
		if (noRender != null && !noRender.armorEnabled()) {
			ci.cancel();
		}
	}
}

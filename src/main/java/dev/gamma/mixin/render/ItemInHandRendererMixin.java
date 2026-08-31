package dev.gamma.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.gamma.modules.render.ViewModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code submitHandsWithItems} is specifically the first-person hands/held-items path (it takes
 * a {@code LocalPlayer}, unlike the shared {@code renderItem} other entities also go through),
 * so it's the correct, narrow target for {@code ViewModel}. The extra transform is pushed at
 * the very head, before vanilla computes its own pose, so it composes underneath vanilla's
 * positioning rather than fighting it.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {

	@Inject(method = "submitHandsWithItems", at = @At("HEAD"))
	private void gamma$viewModelTransform(float partialTick, PoseStack poseStack, SubmitNodeCollector collector, LocalPlayer player, int packedLight, CallbackInfo ci) {
		ViewModel viewModel = ViewModel.instance;
		if (viewModel == null || !viewModel.isEnabled()) {
			return;
		}
		poseStack.translate(viewModel.offsetX(), viewModel.offsetY(), viewModel.offsetZ());
		poseStack.scale((float) viewModel.scale(), (float) viewModel.scale(), (float) viewModel.scale());
		poseStack.mulPose(Axis.XP.rotationDegrees((float) viewModel.rotationX()));
		poseStack.mulPose(Axis.YP.rotationDegrees((float) viewModel.rotationY()));
		poseStack.mulPose(Axis.ZP.rotationDegrees((float) viewModel.rotationZ()));
	}

	// Crashed at launch: "Expected signature: (F, F, PoseStack, I, HumanoidArm)F" -- Mixin wants
	// the captured/modified value PREPENDED to swingArm's complete original parameter list (with
	// that same slot echoed again in its natural position at index 1), not "the modified value
	// replacing its own slot, followed by the rest" as the previous version assumed. The two
	// leading floats are the same attackAnim value; only the first is actually used.
	@ModifyVariable(method = "swingArm", at = @At("HEAD"), argsOnly = true)
	private float gamma$swingSpeed(float attackAnim, float rawAttackAnim, PoseStack poseStack, int light, HumanoidArm arm) {
		ViewModel viewModel = ViewModel.instance;
		if (viewModel == null || !viewModel.isEnabled()) {
			return attackAnim;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || arm != player.getMainArm()) {
			return attackAnim;
		}
		return viewModel.advanceSwing(attackAnim);
	}
}

package dev.gamma.mixin.render;

import dev.gamma.modules.render.NoRender;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@code NoRender}'s item-spin toggle — the rotation the item renderer applies is read straight off the entity via this method, so overriding its return is the whole fix. */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {

	@Inject(method = "getVisualRotationYInDegrees", at = @At("RETURN"), cancellable = true)
	private void gamma$noSpin(CallbackInfoReturnable<Float> cir) {
		NoRender noRender = NoRender.instance;
		if (noRender != null && !noRender.itemSpinEnabled()) {
			cir.setReturnValue(0.0f);
		}
	}
}

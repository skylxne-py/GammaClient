package dev.gamma.mixin.misc;

import dev.gamma.modules.misc.BetterTooltips;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Lets {@code BetterTooltips} give an item a tooltip <em>image</em>, not just extra text lines.
 *
 * <p>Fabric's {@code ItemTooltipCallback} — which the module already uses — hands out a
 * {@code List<Component>} and nothing else, so it can only ever add more text. The picture half of
 * a tooltip comes from a completely separate channel: {@code ItemStack.getTooltipImage()} returns
 * an optional {@code TooltipComponent}, which the client converts into a drawable
 * {@code ClientTooltipComponent}. That conversion has a Fabric event
 * ({@code ClientTooltipComponentCallback}), but producing the {@code TooltipComponent} in the
 * first place does not, and it is a vanilla item method — hence a mixin.
 *
 * <p>An existing return value is always left alone, so vanilla's own image tooltips (bundles most
 * obviously) keep working untouched. All the policy lives in
 * {@link BetterTooltips#tooltipImageFor}; this only forwards.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackTooltipImageMixin {

	@Inject(method = "getTooltipImage", at = @At("RETURN"), cancellable = true)
	private void gamma$addPreviewImage(CallbackInfoReturnable<Optional<TooltipComponent>> cir) {
		BetterTooltips tooltips = BetterTooltips.instance;
		if (tooltips == null) {
			return;
		}
		Optional<TooltipComponent> replacement = tooltips.tooltipImageFor((ItemStack) (Object) this, cir.getReturnValue());
		if (replacement != null) {
			cir.setReturnValue(replacement);
		}
	}
}

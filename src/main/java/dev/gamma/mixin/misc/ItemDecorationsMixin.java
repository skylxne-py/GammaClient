package dev.gamma.mixin.misc;

import dev.gamma.gui.FireworkStrengthOverlay;
import dev.gamma.render.Renderer2D;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets Gamma draw its own overlay on an item slot, wherever that slot is.
 *
 * <p>No event covers this. Fabric has {@code ItemTooltipCallback} for tooltip text and
 * {@code ClientTooltipComponentCallback} for tooltip images, but nothing at all for the badge
 * drawn <em>on</em> the slot — and the badge is the entire point here, since the number has to be
 * readable without hovering. {@code HudRenderCallback} is no substitute: it fires once for the
 * whole HUD and knows nothing about container screens, which is where most of a player's rockets
 * are.
 *
 * <p>{@code itemDecorations} is the right single seam: the hotbar, the inventory, and every
 * container screen all funnel their slots through it, and the four-argument overload delegates
 * straight to this five-argument one — so injecting here catches every slot in the game exactly
 * once. {@code TAIL} rather than {@code HEAD} so the label draws over vanilla's count, durability
 * bar and cooldown overlay rather than under them.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class ItemDecorationsMixin {

	/**
	 * One renderer per extractor rather than one per slot. {@link Renderer2D} is a thin wrapper,
	 * but it carries a scissor deque, and allocating that for every slot of every container frame
	 * is a cost with nothing to show for it.
	 */
	@Unique
	private Renderer2D gamma$renderer;

	@Inject(method = "itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V", at = @At("TAIL"))
	private void gamma$drawSlotOverlays(Font font, ItemStack stack, int x, int y, String countOverride, CallbackInfo ci) {
		if (stack.isEmpty()) {
			return;
		}
		if (gamma$renderer == null) {
			gamma$renderer = new Renderer2D((GuiGraphicsExtractor) (Object) this);
		}
		FireworkStrengthOverlay.draw(gamma$renderer, font, stack, x, y);
	}
}

package dev.gamma.gui.tooltip;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Data half of the shulker-box tooltip preview: just the contents, no rendering.
 *
 * <p>Vanilla's tooltip pipeline is deliberately split — an item produces a {@link TooltipComponent}
 * (plain data, no client classes) and the client turns that into a
 * {@code ClientTooltipComponent} that knows how to draw it. Following that split rather than
 * short-cutting straight to a renderer is what lets {@link dev.gamma.gui.tooltip.GammaTooltips}
 * register the conversion through Fabric's own {@code TooltipComponentCallback} instead of
 * needing a second mixin.
 *
 * @param items contents in slot order, empty slots included, so the grid keeps the layout the
 *              container actually has instead of compacting everything to the top-left
 */
public record ContainerPreviewTooltip(List<ItemStack> items) implements TooltipComponent {
}

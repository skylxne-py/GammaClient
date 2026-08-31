package dev.gamma.gui.tooltip;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Draws a shulker box's contents as the 9×3 grid they actually live in, instead of the flat
 * "- 12x Cobblestone" text list this replaced. A stash is read by shape — a double row of the
 * same block, one slot of something valuable — and a list of names throws that away and takes far
 * more vertical space than the screen has.
 *
 * <p>Empty slots are drawn, not skipped: the gaps are information about how full the box is, and
 * compacting the items to the top-left would move everything off the position it occupies in the
 * real container.
 */
public final class ClientContainerPreviewTooltip implements ClientTooltipComponent {

	private static final int SLOT_SIZE = 18;
	private static final int COLUMNS = 9;
	private static final int BORDER = 1;

	/** Vanilla's inventory slot grey, so this sits in a vanilla tooltip without looking pasted in. */
	private static final int SLOT_BACKGROUND = 0xFF8B8B8B;
	private static final int SLOT_SHADOW = 0xFF373737;

	private final List<ItemStack> items;
	private final int rows;

	public ClientContainerPreviewTooltip(ContainerPreviewTooltip tooltip) {
		this.items = tooltip.items();
		this.rows = Math.max(1, (int) Math.ceil(items.size() / (double) COLUMNS));
	}

	@Override
	public int getWidth(Font font) {
		return COLUMNS * SLOT_SIZE + BORDER * 2;
	}

	@Override
	public int getHeight(Font font) {
		return rows * SLOT_SIZE + BORDER * 2;
	}

	@Override
	public void extractImage(Font font, int x, int y, int width, int height, GuiGraphicsExtractor extractor) {
		for (int index = 0; index < rows * COLUMNS; index++) {
			int slotX = x + BORDER + (index % COLUMNS) * SLOT_SIZE;
			int slotY = y + BORDER + (index / COLUMNS) * SLOT_SIZE;
			drawSlot(extractor, slotX, slotY);
			if (index < items.size()) {
				ItemStack stack = items.get(index);
				if (!stack.isEmpty()) {
					// The 1px inset centres a 16px item icon inside an 18px slot.
					extractor.item(stack, slotX + 1, slotY + 1, index);
					// Stack counts, durability bars and cooldowns, same as a real slot. Without this
					// a box of 64-stacks and a box of single items looked identical, which defeats
					// the point of reading a stash by shape.
					extractor.itemDecorations(font, stack, slotX + 1, slotY + 1);
				}
			}
		}
	}

	/** Two rects rather than a nine-slice sprite: a flat cell with a shadowed edge reads the same at this size and needs no texture lookup. */
	private void drawSlot(GuiGraphicsExtractor extractor, int slotX, int slotY) {
		extractor.fill(slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, SLOT_SHADOW);
		extractor.fill(slotX + 1, slotY + 1, slotX + SLOT_SIZE - 1, slotY + SLOT_SIZE - 1, SLOT_BACKGROUND);
	}
}

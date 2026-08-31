package dev.gamma.gui.hud.elements;

import dev.gamma.gui.hud.Anchor;
import dev.gamma.gui.hud.HudComponent;
import dev.gamma.gui.hud.HudContext;
import dev.gamma.render.Renderer2D;
import net.minecraft.client.gui.Font;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Worn armor with durability bars (vanilla's own {@code itemDecorations} draws the bar). */
public final class ArmorElement extends HudComponent {

	private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	private static final int ICON_SIZE = 16;
	private static final int GAP = 2;

	public ArmorElement() {
		super("armor", "Armor", Anchor.BOTTOM_CENTER, 0, 60);
	}

	private List<ItemStack> stacks(HudContext ctx) {
		if (!ctx.hasPlayer()) {
			return List.of();
		}
		List<ItemStack> stacks = new ArrayList<>();
		for (EquipmentSlot slot : SLOTS) {
			ItemStack stack = ctx.player().getItemBySlot(slot);
			if (!stack.isEmpty()) {
				stacks.add(stack);
			}
		}
		return stacks;
	}

	@Override
	public int measureWidth(Font font, HudContext ctx) {
		int count = stacks(ctx).size();
		return count == 0 ? 0 : count * ICON_SIZE + (count - 1) * GAP;
	}

	@Override
	public int measureHeight(Font font, HudContext ctx) {
		return ICON_SIZE;
	}

	@Override
	public void render(Renderer2D renderer, Font font, int x, int y, HudContext ctx) {
		int cursor = x;
		for (ItemStack stack : stacks(ctx)) {
			renderer.item(stack, cursor, y);
			renderer.itemDecorations(font, stack, cursor, y);
			cursor += ICON_SIZE + GAP;
		}
	}
}

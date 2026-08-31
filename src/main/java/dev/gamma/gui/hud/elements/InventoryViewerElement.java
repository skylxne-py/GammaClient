package dev.gamma.gui.hud.elements;

import dev.gamma.gui.hud.Anchor;
import dev.gamma.gui.hud.HudComponent;
import dev.gamma.gui.hud.HudContext;
import dev.gamma.render.Renderer2D;
import net.minecraft.client.gui.Font;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Hover another player to see their armor and held item — invisible (zero size) otherwise. */
public final class InventoryViewerElement extends HudComponent {

	private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND};
	private static final int ICON_SIZE = 16;
	private static final int GAP = 2;

	public InventoryViewerElement() {
		super("inventory_viewer", "Inventory Viewer", Anchor.MIDDLE_LEFT, 6, 60);
	}

	private Player hoveredPlayer(HudContext ctx) {
		Object entity = ctx.client().crosshairPickEntity;
		if (entity instanceof Player player && player != ctx.player()) {
			return player;
		}
		return null;
	}

	private List<ItemStack> stacks(HudContext ctx) {
		Player player = hoveredPlayer(ctx);
		if (player == null) {
			return List.of();
		}
		List<ItemStack> stacks = new ArrayList<>();
		for (EquipmentSlot slot : SLOTS) {
			ItemStack stack = player.getItemBySlot(slot);
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
		return stacks(ctx).isEmpty() ? 0 : ICON_SIZE;
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

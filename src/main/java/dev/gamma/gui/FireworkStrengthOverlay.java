package dev.gamma.gui;

import dev.gamma.config.GammaSettings;
import dev.gamma.render.Renderer2D;
import net.minecraft.client.gui.Font;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Fireworks;

/**
 * Stamps a firework rocket's flight duration (its "strength", 1-3) into the corner of the item
 * slot. Vanilla only says this in the tooltip, which is no use when you are picking a rocket out
 * of the hotbar mid-flight.
 *
 * <p>Not a {@link dev.gamma.core.Module}: it has no enabled/disabled lifecycle worth the name, no
 * keybind anyone would bind, and no category it belongs in — it is one boolean. It lives in
 * {@link GammaSettings} alongside the other client-wide preferences for the same reason those do,
 * and this class is just the draw half.
 *
 * <p>Top-left corner and a fixed yellow, deliberately not configurable: bottom-right is the stack
 * count and rockets are carried stacked, the bottom edge is the durability bar, and a label this
 * small has no room to be subtle about colour.
 *
 * <p>{@code flightDuration} is read straight off the {@code minecraft:fireworks} component, so
 * anything carrying that component is labelled — including server-made rockets outside the vanilla
 * 1-3 range, which show whatever they actually say rather than being clamped.
 */
public final class FireworkStrengthOverlay {

	private static final int COLOR = 0xFFFFDD55;
	private static final int INSET = 1;

	private FireworkStrengthOverlay() {
	}

	/** {@code x}/{@code y} are the slot's top-left in GUI space, matching vanilla's own decorations. */
	public static void draw(Renderer2D renderer, Font font, ItemStack stack, int x, int y) {
		if (!GammaSettings.fireworkStrength()) {
			return;
		}
		Fireworks fireworks = stack.get(DataComponents.FIREWORKS);
		if (fireworks == null) {
			return;
		}
		// Shadowed explicitly: the label sits on an item model of unknown colour, and the renderer's
		// shadow flag is whatever the GUI that owns this frame last set it to.
		renderer.text(font, Integer.toString(fireworks.flightDuration()), x + INSET, y + INSET, COLOR, true);
	}
}

package dev.gamma.gui.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

/** Per-frame state handed to every {@link HudComponent}, resolved once by {@link HudManager} instead of each element re-deriving it. */
public record HudContext(
		Minecraft client,
		LocalPlayer player,
		ClientLevel level,
		int screenWidth,
		int screenHeight,
		float partialTick,
		double tps,
		long sessionStartMillis) {

	public boolean hasPlayer() {
		return player != null;
	}
}

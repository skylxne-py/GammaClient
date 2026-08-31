package dev.gamma.gui.hud.elements;

import dev.gamma.gui.hud.Anchor;
import dev.gamma.gui.hud.HudContext;
import dev.gamma.gui.hud.SingleLineHudComponent;

public final class DirectionElement extends SingleLineHudComponent {

	// Index by round(yaw / 45) mod 8. Minecraft yaw: 0 = south, increasing clockwise (90 = west, 180 = north, 270 = east).
	private static final String[] COMPASS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

	public DirectionElement() {
		super("direction", "Direction", Anchor.BOTTOM_LEFT, 6, 32);
	}

	@Override
	protected String text(HudContext ctx) {
		if (!ctx.hasPlayer()) {
			return "--";
		}
		double yaw = ((ctx.player().getYRot() % 360) + 360) % 360;
		int index = (int) Math.floorMod(Math.round(yaw / 45.0), 8);
		return COMPASS[index] + " (" + Math.round(yaw) + "°)";
	}
}

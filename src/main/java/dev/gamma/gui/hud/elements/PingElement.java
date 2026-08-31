package dev.gamma.gui.hud.elements;

import dev.gamma.gui.hud.Anchor;
import dev.gamma.gui.hud.HudContext;
import dev.gamma.gui.hud.SingleLineHudComponent;
import net.minecraft.client.multiplayer.PlayerInfo;

public final class PingElement extends SingleLineHudComponent {

	public PingElement() {
		super("ping", "Ping", Anchor.BOTTOM_RIGHT, 6, 6);
	}

	@Override
	protected String text(HudContext ctx) {
		if (!ctx.hasPlayer() || ctx.client().getConnection() == null) {
			return "-- ms";
		}
		PlayerInfo info = ctx.client().getConnection().getPlayerInfo(ctx.player().getUUID());
		return (info != null ? info.getLatency() : "--") + " ms";
	}
}

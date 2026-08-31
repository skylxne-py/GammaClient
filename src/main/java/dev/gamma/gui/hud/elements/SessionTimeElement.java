package dev.gamma.gui.hud.elements;

import dev.gamma.gui.hud.Anchor;
import dev.gamma.gui.hud.HudContext;
import dev.gamma.gui.hud.SingleLineHudComponent;

public final class SessionTimeElement extends SingleLineHudComponent {

	public SessionTimeElement() {
		super("session_time", "Session Time", Anchor.BOTTOM_RIGHT, 6, 34);
	}

	@Override
	protected String text(HudContext ctx) {
		long seconds = (System.currentTimeMillis() - ctx.sessionStartMillis()) / 1000;
		long h = seconds / 3600;
		long m = (seconds % 3600) / 60;
		long s = seconds % 60;
		return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
	}
}

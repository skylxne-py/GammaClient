package dev.gamma.gui.hud.elements;

import dev.gamma.gui.hud.Anchor;
import dev.gamma.gui.hud.HudContext;
import dev.gamma.gui.hud.SingleLineHudComponent;

public final class FpsElement extends SingleLineHudComponent {

	public FpsElement() {
		super("fps", "FPS", Anchor.BOTTOM_LEFT, 6, 6);
	}

	@Override
	protected String text(HudContext ctx) {
		return ctx.client().getFps() + " fps";
	}
}

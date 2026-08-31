package dev.gamma.gui.hud.elements;

import dev.gamma.gui.hud.Anchor;
import dev.gamma.gui.hud.HudContext;
import dev.gamma.gui.hud.SingleLineHudComponent;

/** TPS estimated by {@link dev.gamma.gui.hud.TpsTracker} from client tick spacing — see the design notes. */
public final class TpsElement extends SingleLineHudComponent {

	public TpsElement() {
		super("tps", "TPS", Anchor.BOTTOM_RIGHT, 6, 20);
	}

	@Override
	protected String text(HudContext ctx) {
		return String.format("%.1f tps", ctx.tps());
	}
}

package dev.gamma.gui.hud;

import dev.gamma.render.Renderer2D;
import net.minecraft.client.gui.Font;

/** Shared shape for the several default HUD elements that are just one line of text (FPS, ping, TPS, ...). */
public abstract class SingleLineHudComponent extends HudComponent {

	protected SingleLineHudComponent(String id, String displayName, Anchor defaultAnchor, double defaultOffsetX, double defaultOffsetY) {
		super(id, displayName, defaultAnchor, defaultOffsetX, defaultOffsetY);
	}

	protected abstract String text(HudContext ctx);

	@Override
	public int measureWidth(Font font, HudContext ctx) {
		return font.width(text(ctx));
	}

	@Override
	public int measureHeight(Font font, HudContext ctx) {
		return font.lineHeight;
	}

	@Override
	public void render(Renderer2D renderer, Font font, int x, int y, HudContext ctx) {
		renderer.text(font, text(ctx), x, y, color());
	}
}

package dev.gamma.gui.hud.elements;

import dev.gamma.gui.GammaLogo;
import dev.gamma.gui.hud.Anchor;
import dev.gamma.gui.hud.HudComponent;
import dev.gamma.gui.hud.HudContext;
import dev.gamma.render.Renderer2D;
import net.minecraft.client.gui.Font;

/**
 * The client's wordmark, top-left by default.
 *
 * <p>Was a {@code SingleLineHudComponent} drawing the literal string "Gamma Client" in the theme
 * accent; it draws {@link GammaLogo} now, so it extends {@link HudComponent} directly — there is
 * no line of text for the single-line base class to lay out.
 *
 * <p>The artwork is two-tone, so the element's colour is read for its <em>alpha only</em> and the
 * RGB is forced to white: multiplying by a picked colour would recolour the "Client" half and
 * muddy the "Gamma" half, whereas fading the whole mark is a thing someone might actually want
 * from a watermark. The colour swatch in the HUD editor therefore behaves as an opacity slider
 * here rather than a tint.
 */
public final class WatermarkElement extends HudComponent {

	/** Unscaled height; {@link dev.gamma.gui.hud.HudManager} applies the element's own scale on top. */
	private static final int HEIGHT = 12;

	public WatermarkElement() {
		super("watermark", "Watermark", Anchor.TOP_LEFT, 6, 6);
	}

	@Override
	public int measureWidth(Font font, HudContext ctx) {
		return GammaLogo.widthFor(HEIGHT);
	}

	@Override
	public int measureHeight(Font font, HudContext ctx) {
		return HEIGHT;
	}

	@Override
	public void render(Renderer2D renderer, Font font, int x, int y, HudContext ctx) {
		GammaLogo.render(renderer, x, y, HEIGHT, GammaLogo.fade(((color() >>> 24) & 0xFF) / 255.0));
	}
}

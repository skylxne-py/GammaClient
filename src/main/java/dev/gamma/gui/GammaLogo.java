package dev.gamma.gui;

import dev.gamma.render.Renderer2D;
import net.minecraft.resources.Identifier;

/**
 * The Gamma Client wordmark, drawn from {@code assets/gamma/textures/gui/logo.png}.
 *
 * <p>This replaces a hand-drawn 8x9 pixel "G" grid that lived in {@code clickgui} and tinted
 * itself with {@link dev.gamma.gui.clickgui.Theme#accent()}. The artwork carries its own two-tone
 * colouring, so the accent tint is gone: multiplying a coral-and-white image by the accent would
 * recolour the "Client" half too and muddy the "Gamma" half. Callers pass white with the alpha
 * they want instead, which is what {@link #fade(double)} is for — the ClickGUI's entry animation
 * needs to fade the logo in with the rest of the window.
 *
 * <p>It moved out of {@code clickgui} because three unrelated places now draw it: the ClickGUI
 * header, the HUD watermark, and the title screen.
 *
 * <h2>Source size</h2>
 *
 * <p>The shipped PNG is 512x75, downscaled from the 1461x215 original. It is drawn at roughly
 * 18-28px tall, and GUI scale multiplies that by up to 4, so 512 wide is about one source pixel
 * per physical pixel at the largest scale anyone will use — big enough to stay sharp, small
 * enough not to ship a 55KB texture to draw a 122px logo. The accompanying {@code .mcmeta} sets
 * {@code blur} so the downscale is filtered rather than point-sampled; a nearest-neighbour
 * wordmark at a non-integer ratio drops whole strokes off the thin letters.
 */
public final class GammaLogo {

	public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("gamma", "textures/gui/logo.png");

	/** The shipped PNG's own dimensions — the sheet size {@link Renderer2D#texture} samples against. */
	private static final int SOURCE_WIDTH = 512;
	private static final int SOURCE_HEIGHT = 75;

	private GammaLogo() {
	}

	/** Width the wordmark occupies when drawn {@code height} pixels tall, for layout purposes. */
	public static int widthFor(int height) {
		return Math.round(height * (float) SOURCE_WIDTH / SOURCE_HEIGHT);
	}

	/** White at {@code alpha} of full — the tint to pass {@link #render} for an undistorted logo. */
	public static int fade(double alpha) {
		int a = (int) Math.round(Math.max(0.0, Math.min(1.0, alpha)) * 255.0);
		return (a << 24) | 0xFFFFFF;
	}

	/** Draws the wordmark with its top-left at {@code x}/{@code y}, scaled to {@code height}. */
	public static void render(Renderer2D renderer, int x, int y, int height, int tint) {
		renderer.texture(TEXTURE, x, y, widthFor(height), height, SOURCE_WIDTH, SOURCE_HEIGHT, tint);
	}
}

package dev.gamma.gui;

import dev.gamma.config.GammaSettings;
import dev.gamma.render.Renderer2D;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Draws {@link GammaLogo} in the corner of the vanilla title screen.
 *
 * <p>No mixin: Fabric's {@code ScreenEvents.afterExtract} hands over the very
 * {@link net.minecraft.client.gui.GuiGraphicsExtractor} that {@link Renderer2D} already wraps,
 * after the screen has extracted its own render state — so the wordmark layers over the panorama
 * and buttons with nothing injected into vanilla code.
 *
 * <h2>Placement</h2>
 *
 * <p>Top-left, but at {@code y = 4} with a 20px height rather than the more natural 8/24: vanilla's
 * own logo is 256x44 drawn centred at {@code y = 30} ({@code LogoRenderer.DEFAULT_HEIGHT_OFFSET}),
 * and at a large GUI scale the scaled width shrinks until a centred 256px logo reaches into the
 * left margin. Ending at {@code y = 24} clears it vertically at every width, so the two never
 * collide however the window is sized.
 *
 * <h2>Register on every init, unconditionally</h2>
 *
 * <p>This used to skip re-registering when the screen instance was one it had already seen, on the
 * theory that a screen re-initialised in place (which is what {@code resize()} does) would stack a
 * second draw. That is backwards, and it is why the logo was missing on the title screen at startup
 * but present after returning to it from a world.
 *
 * <p>Fabric's per-screen events are rebuilt from scratch inside {@code Screen.init}: its mixin
 * replaces every one of them — {@code afterRender} included, which is what {@code afterExtract}
 * hands back — with a fresh empty event <em>before</em> vanilla's {@code init} body runs, then
 * fires {@code AFTER_INIT} so listeners can re-register onto the new one. Duplicates cannot
 * accumulate; what can happen is exactly what did. The title screen is initialised more than once
 * during startup, the second init discarded the handler, and the guard then refused to put it back.
 * A fresh {@code TitleScreen} instance on the way back from a world got past the guard, which is
 * what made the bug look like it was about world loading.
 */
public final class TitleScreenLogo {

	private static final int HEIGHT = 20;
	private static final int MARGIN_X = 8;
	private static final int MARGIN_Y = 4;

	private TitleScreenLogo() {
	}

	public static void install() {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!(screen instanceof TitleScreen)) {
				return;
			}
			ScreenEvents.afterExtract(screen).register((s, extractor, mouseX, mouseY, tickDelta) -> {
				if (!GammaSettings.titleScreenLogo()) {
					return;
				}
				GammaLogo.render(new Renderer2D(extractor), MARGIN_X, MARGIN_Y, HEIGHT, GammaLogo.fade(1.0));
			});
		});
	}
}

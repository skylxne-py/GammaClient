package dev.gamma.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Base for Gamma's own full-screen UIs. Exists to carry two overrides that every one of them needs
 * and that are quietly fatal to omit.
 *
 * <h2>{@code isInGameUi}</h2>
 *
 * <p>{@link Screen#extractBackground} branches on this. Left at vanilla's {@code false}, it takes
 * the menu-screen path and calls {@code extractBlurredBackground}, which calls
 * {@code blurBeforeThisStratum} whenever the "Menu Background Blurriness" option is 1 or more.
 * Any screen that then blurs for itself in {@code extractRenderState} — which all of ours do, since
 * a frosted backdrop is the look — makes that the second blur of the frame, and
 * {@code GuiRenderState.blurBeforeThisStratum} throws {@code IllegalStateException: Can only blur
 * once per frame}. The crash is immediate and total: the screen cannot be opened at all.
 *
 * <p>Reporting these as in-game UI takes the {@code extractTransparentBackground} path instead,
 * which leaves the live world visible behind them and makes our own blur the only one — which is
 * both correct for what they are and the thing that stops the crash.
 *
 * <p>This lived as a copy-pasted override with a copy-pasted comment on three screens, and the
 * fourth was written without it and crashed on open. Inheriting it is the only version that can't
 * be forgotten.
 *
 * <h2>{@code isPauseScreen}</h2>
 *
 * <p>These overlay the running game; singleplayer should keep ticking underneath rather than
 * freezing because a settings panel is open.
 */
public abstract class GammaScreen extends Screen {

	protected GammaScreen(Component title) {
		super(title);
	}

	@Override
	public final boolean isPauseScreen() {
		return false;
	}

	@Override
	public final boolean isInGameUi() {
		return true;
	}
}

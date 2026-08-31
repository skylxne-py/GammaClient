package dev.gamma.gui.account;

import dev.gamma.account.AccountManager;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

/**
 * Puts the account switcher in the top-right corner of the title screen and the server list.
 *
 * <p>The button is labelled with the account you are currently signed in as, so it does double duty
 * as the indicator — on the two screens where you are about to pick a server, the name you would
 * join with is on screen without opening anything.
 *
 * <h2>Register on every init, unconditionally</h2>
 *
 * <p>Fabric rebuilds a screen's widget list and every per-screen event inside {@code Screen.init},
 * which runs again on each resize, then fires {@code AFTER_INIT} so listeners can re-attach.
 * Adding the button on every {@code AFTER_INIT} is therefore correct and cannot accumulate
 * duplicates — the previous list is gone. Guarding against "already added" is the bug, not the fix:
 * it is what left the title screen logo missing at startup, and it would drop this button on the
 * first resize. See the design notes, 2026-08-27.
 */
public final class AccountButtons {

	private static final int HEIGHT = 20;
	private static final int MARGIN = 6;
	private static final int PADDING = 12;
	private static final int MIN_WIDTH = 70;
	private static final int MAX_WIDTH = 120;

	private AccountButtons() {
	}

	public static void install() {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen instanceof TitleScreen || screen instanceof JoinMultiplayerScreen) {
				Screens.getWidgets(screen).add(build(client, screen));
			}
		});
	}

	private static Button build(Minecraft client, Screen screen) {
		String label = AccountManager.currentName();
		int width = Math.clamp(client.font.width(label) + PADDING, MIN_WIDTH, MAX_WIDTH);
		return Button.builder(Component.literal(label), button -> client.setScreenAndShow(new AccountScreen(screen)))
				.bounds(screen.width - width - MARGIN, MARGIN, width, HEIGHT)
				.tooltip(net.minecraft.client.gui.components.Tooltip.create(
						Component.literal("Switch Minecraft account")))
				.build();
	}
}

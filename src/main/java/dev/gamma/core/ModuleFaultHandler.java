package dev.gamma.core;

import dev.gamma.Gamma;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Central catch for exceptions escaping a module's event handlers — every subscription made
 * through {@link Module#listen} routes here. Per the project conventions/Phase 7 hardening: a module that
 * throws gets caught, disabled, and reported in chat rather than crashing the client.
 */
final class ModuleFaultHandler {

	private ModuleFaultHandler() {
	}

	static void handle(Module module, Exception error) {
		Gamma.LOGGER.error("{} threw in an event handler — disabling it", module.name(), error);
		module.setEnabled(false);

		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			player.sendSystemMessage(Component.literal(
					"[Gamma] " + module.name() + " crashed and was disabled: " + describe(error)));
		}
	}

	private static String describe(Exception error) {
		String message = error.getMessage();
		return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
	}
}

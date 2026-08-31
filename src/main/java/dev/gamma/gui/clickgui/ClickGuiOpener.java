package dev.gamma.gui.clickgui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.gamma.config.ConfigManager;
import dev.gamma.config.GammaSettings;
import dev.gamma.config.setting.KeybindSetting;
import dev.gamma.core.ModuleRegistry;
import dev.gamma.core.event.EventBus;
import dev.gamma.core.event.events.TickEvent;
import dev.gamma.core.keybind.TextInputCapture;
import dev.gamma.gui.hud.HudManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Polls the menu key once per tick, same edge-triggered approach as {@code KeybindManager}, to
 * open/close the ClickGUI. Only acts when no screen is open (or the ClickGUI already is) so it
 * never steals the key from chat, inventory, or any other menu. The key itself is a client setting
 * ({@code GammaSettings.MenuKey}), defaulting to GRAVE.
 */
public final class ClickGuiOpener {

	private final ModuleRegistry registry;
	private final Theme theme;
	private final GuiConfig guiConfig;
	private final HudManager hudManager;
	private final ConfigManager configManager;
	private final GammaSettings settings;
	private boolean wasDown;

	public ClickGuiOpener(ModuleRegistry registry, Theme theme, GuiConfig guiConfig, HudManager hudManager, ConfigManager configManager, GammaSettings settings, EventBus eventBus) {
		this.registry = registry;
		this.theme = theme;
		this.guiConfig = guiConfig;
		this.hudManager = hudManager;
		this.configManager = configManager;
		this.settings = settings;
		eventBus.subscribe(TickEvent.class, event -> {
			if (event.phase() == TickEvent.Phase.END) {
				tick();
			}
		});
	}

	public void open() {
		Minecraft client = Minecraft.getInstance();
		if (client.gui.screen() instanceof GammaGuiScreen) {
			return;
		}
		client.gui.setScreen(new ModernGuiScreen(registry, theme, guiConfig, hudManager, configManager, settings));
	}

	private void tick() {
		Minecraft client = Minecraft.getInstance();
		// RIGHT SHIFT only until the settings have loaded, and only as the default afterwards: a key
		// that exists in the same place on every layout, unlike GRAVE, which it replaced. Still a
		// preference. Mode is ignored -- opening a menu is a press, not something you hold.
		GammaSettings settings = GammaSettings.instance;
		KeybindSetting.Bind bind = settings == null ? null : settings.menuKey().get();
		int key = bind == null || !bind.isBound() ? InputConstants.KEY_RSHIFT : bind.keyCode();
		boolean down = InputConstants.isKeyDown(client.getWindow(), key);
		if (down && !wasDown) {
			Screen current = client.gui.screen();
			// Not while a text field has focus. This poll runs regardless of what has the keyboard,
			// so without the check the bind both closed the GUI out from under whatever was being
			// typed and ate the character if the bind key happened to be a printable one.
			if (current instanceof TextInputCapture capture && capture.isCapturingTextInput()) {
				wasDown = true;
				return;
			}
			if (current instanceof GammaGuiScreen) {
				client.gui.setScreen(null);
			} else if (current == null) {
				open();
			}
		}
		wasDown = down;
	}
}

package dev.gamma.core.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import dev.gamma.config.GammaSettings;
import dev.gamma.config.setting.KeybindSetting;
import dev.gamma.core.Module;
import dev.gamma.core.ModuleRegistry;
import dev.gamma.core.event.EventBus;
import dev.gamma.core.event.events.TickEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Polls key state once per tick (via {@code InputConstants.isKeyDown}) rather than mixing into
 * {@code KeyboardHandler} — vanilla's key-press callback only fires on press/repeat, not
 * release, which HOLD-mode binds need. Polling also sidesteps needing a mixin entirely.
 */
public final class KeybindManager {

	private final ModuleRegistry registry;
	private final Map<Module, Boolean> wasDown = new HashMap<>();
	private final Map<Module, Integer> trackedKey = new HashMap<>();

	public KeybindManager(ModuleRegistry registry, EventBus eventBus) {
		this.registry = registry;
		eventBus.subscribe(TickEvent.class, event -> {
			if (event.phase() == TickEvent.Phase.END) {
				tick();
			}
		});
	}

	private void tick() {
		Minecraft client = Minecraft.getInstance();
		if (client.gui.screen() != null) {
			suspend(client);
			return;
		}
		for (Module module : registry.all()) {
			KeybindSetting.Bind bind = module.keybind().get();
			if (!bind.isBound()) {
				wasDown.remove(module);
				trackedKey.remove(module);
				continue;
			}
			boolean down = InputConstants.isKeyDown(client.getWindow(), bind.keyCode());
			Integer previousKey = trackedKey.put(module, bind.keyCode());
			if (previousKey == null || previousKey != bind.keyCode()) {
				// The bind just changed (e.g. rebound from the ClickGUI, where the key used to
				// confirm the new bind is often still physically held). Seed state from the
				// current key rather than treating it as a fresh press, so that key doesn't also
				// fire the module this tick.
				wasDown.put(module, down);
				continue;
			}
			boolean previouslyDown = wasDown.getOrDefault(module, false);
			wasDown.put(module, down);
			if (down == previouslyDown) {
				continue;
			}
			boolean before = module.isEnabled();
			if (bind.mode() == KeybindSetting.Mode.HOLD) {
				module.setEnabled(down);
			} else if (down) {
				// TOGGLE mode fires on the press edge only, not the release.
				module.toggle();
			} else {
				continue;
			}
			// Compared rather than assumed: a module that throws in onEnable is disabled again by
			// ModuleFaultHandler before this line, and reporting "enabled" for something that just
			// crashed and switched itself back off would be worse than saying nothing.
			if (GammaSettings.chatMessagesEnabled() && module.announcesToggle() && module.isEnabled() != before) {
				announce(module);
			}
		}
	}

	/**
	 * Says what a bind just did, in chat.
	 *
	 * <p>Bind-driven changes only. A module toggled from the ClickGUI or {@code .toggle} doesn't come
	 * through here and shouldn't: you are looking at the thing you changed in both cases, and the
	 * command already prints its own confirmation. A keypress during gameplay is the one path where
	 * the state changed and nothing on screen necessarily said so.
	 */
	private void announce(Module module) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		boolean enabled = module.isEnabled();
		player.sendSystemMessage(Component.literal("[Gamma] ").withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal(module.name()).withStyle(ChatFormatting.WHITE))
				.append(Component.literal(" " + (enabled ? "enabled" : "disabled"))
						.withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED)));
	}

	/** Other modules currently bound to the same key as {@code module}, for GUI/command warnings. */
	public List<Module> conflictsFor(Module module) {
		KeybindSetting.Bind bind = module.keybind().get();
		if (!bind.isBound()) {
			return List.of();
		}
		return registry.all().stream()
				.filter(other -> other != module)
				.filter(other -> other.keybind().get().keyCode() == bind.keyCode())
				.toList();
	}

	/**
	 * Binds fire during gameplay only — any open screen suspends them.
	 *
	 * <p>This used to suspend only while a text field had focus, which missed most of the problem:
	 * a search box that hasn't been clicked into yet, the ClickGUI itself, the inventory, the
	 * pause menu and every vanilla screen all still let a bare letter key toggle a module out from
	 * under you. Typing "e" into a search field is not a request to toggle whatever is bound to E.
	 *
	 * <p>Two things have to happen for the suspension to be clean:
	 *
	 * <ul>
	 * <li>{@code wasDown} is reseeded from the live key state every tick the screen is open, so
	 *     closing a screen while still holding the key reads as "already down" rather than as a
	 *     fresh press edge that fires the module immediately.</li>
	 * <li>HOLD binds are released. Their whole contract is "active while the key is down", and
	 *     the key is not meaningfully down once input is going somewhere else — without this,
	 *     opening a screen mid-hold left the module stuck on with no release edge coming.</li>
	 * </ul>
	 */
	private void suspend(Minecraft client) {
		for (Module module : registry.all()) {
			KeybindSetting.Bind bind = module.keybind().get();
			if (!bind.isBound()) {
				continue;
			}
			wasDown.put(module, InputConstants.isKeyDown(client.getWindow(), bind.keyCode()));
			if (bind.mode() == KeybindSetting.Mode.HOLD) {
				module.setEnabled(false);
			}
		}
	}
}

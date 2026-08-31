package dev.gamma.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.blaze3d.platform.InputConstants;
import dev.gamma.Gamma;
import dev.gamma.core.GammaExecutor;
import dev.gamma.core.GammaPaths;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.KeybindSetting;
import dev.gamma.config.setting.Setting;
import dev.gamma.config.setting.StringSetting;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Client-wide preferences that aren't a feature: things that are true of Gamma itself rather than of
 * any one module.
 *
 * <h2>Why not a module</h2>
 *
 * <p>These could each have been a module with a single setting, and that would have been worse. A
 * module has an enabled state, a keybind, and a category, and none of those mean anything for "do
 * chat notifications appear". It would also put client plumbing in the same list as the features,
 * which is exactly the list you scroll looking for a feature.
 *
 * <p>So this borrows the {@link Setting} hierarchy without borrowing {@code Module}: the same
 * declared fields, the same JSON round-trip, the same widgets in the GUI — just no lifecycle. That
 * keeps one settings system rather than two, which is the part worth having.
 *
 * <h2>Persistence</h2>
 *
 * <p>Client-wide, so {@code gamma/settings.json} rather than a per-server profile: which key opens
 * the menu is not a property of the server you happen to be on. Saved on request rather than on
 * every change, because {@link Setting} has no change hook and adding one for this would mean every
 * module setting paying for a listener it never uses — the settings screen calls
 * {@link #requestSave()} when it closes, which is the only moment any of these can have changed.
 *
 * <p>Read through the static accessors rather than the instance: the call sites are a mixin and the
 * keybind poller, neither of which has anywhere to be handed a reference. They answer with the
 * compiled-in default before {@link #load()} has finished, which is correct — a preference that
 * hasn't loaded yet is a preference at its default.
 */
public final class GammaSettings {

	private static final long SAVE_DEBOUNCE_MS = 500;

	public static volatile GammaSettings instance;

	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final List<Setting<?>> settings = new ArrayList<>();
	private volatile ScheduledFuture<?> pendingSave;

	private final BoolSetting chatMessages = register(new BoolSetting("ChatMessages",
			"Announce a module in chat when its keybind turns it on or off. Modules toggled from this menu stay silent — you are already looking at them.", true));
	private final KeybindSetting menuKey = register(new KeybindSetting("MenuKey",
			"Key that opens this menu.", new KeybindSetting.Bind(InputConstants.KEY_RSHIFT, KeybindSetting.Mode.TOGGLE)));
	private final BoolSetting hud = register(new BoolSetting("Hud",
			"Master switch for every Gamma HUD element. Off hides the lot without disturbing which ones you had enabled.", true));
	private final BoolSetting fireworkStrength = register(new BoolSetting("FireworkStrength",
			"Stamp a firework rocket's flight duration (1-3) on its item slot, so you can pick one without hovering.", true));
	private final BoolSetting titleScreenLogo = register(new BoolSetting("TitleScreenLogo",
			"Draw the Gamma wordmark in the top-left corner of the title screen.", true));
	private final StringSetting msaClientId = register(new StringSetting("MsaClientId",
			"Microsoft application id the account switcher signs in with. Blank uses the built-in launcher id, which works but has to ask you to paste the browser's address back once per account. Paste an Azure application id here instead and sign-in completes by itself with nothing to copy.", ""));
	private final BoolSetting bypassBlockedServers = register(new BoolSetting("BypassBlockedServers",
			"Connect to servers on Mojang's blocklist. Vanilla refuses to resolve their addresses at all; this skips that check.", true));

	public GammaSettings() {
		instance = this;
	}

	private <S extends Setting<?>> S register(S setting) {
		settings.add(setting);
		return setting;
	}

	/** Every declared setting, in declaration order — what the settings screen renders. */
	public List<Setting<?>> settings() {
		return Collections.unmodifiableList(settings);
	}

	public KeybindSetting menuKey() {
		return menuKey;
	}

	/** @see #chatMessages */
	public static boolean chatMessagesEnabled() {
		GammaSettings current = instance;
		return current == null || current.chatMessages.get();
	}

	/** @see #hud */
	public static boolean hudEnabled() {
		GammaSettings current = instance;
		return current == null || current.hud.get();
	}

	/** @see #fireworkStrength */
	public static boolean fireworkStrength() {
		GammaSettings current = instance;
		return current == null || current.fireworkStrength.get();
	}

	/** @see #titleScreenLogo */
	public static boolean titleScreenLogo() {
		GammaSettings current = instance;
		return current == null || current.titleScreenLogo.get();
	}

	/** @see #msaClientId */
	public static String msaClientId() {
		GammaSettings current = instance;
		return current == null ? "" : current.msaClientId.get();
	}

	/** @see #bypassBlockedServers */
	public static boolean bypassBlockedServers() {
		GammaSettings current = instance;
		return current != null && current.bypassBlockedServers.get();
	}

	/** Reads off the client thread, then applies on it — call once at startup. */
	public void load() {
		GammaExecutor.execute(() -> {
			JsonObject root = readJson(path());
			Minecraft.getInstance().execute(() -> apply(root));
		});
	}

	private void apply(JsonObject root) {
		if (root == null || !root.has("settings")) {
			return;
		}
		JsonObject json = root.getAsJsonObject("settings");
		for (Setting<?> setting : settings) {
			if (!json.has(setting.name())) {
				continue;
			}
			try {
				setting.fromJson(json.get(setting.name()));
			} catch (RuntimeException e) {
				Gamma.LOGGER.warn("settings.json: failed to load '{}', keeping default", setting.name(), e);
			}
		}
	}

	/**
	 * Writes without the debounce — for shutdown, where the debounced write would be scheduled 500ms
	 * into a window the executor is being torn down inside. Same reasoning as
	 * {@link ConfigManager#saveNow}.
	 */
	public void saveNow() {
		JsonObject root = snapshot();
		GammaExecutor.execute(() -> writeJson(path(), root));
	}

	public void requestSave() {
		ScheduledFuture<?> previous = pendingSave;
		if (previous != null) {
			previous.cancel(false);
		}
		JsonObject snapshot = snapshot();
		pendingSave = GammaExecutor.schedule(() -> writeJson(path(), snapshot), SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
	}

	private JsonObject snapshot() {
		JsonObject json = new JsonObject();
		for (Setting<?> setting : settings) {
			json.add(setting.name(), setting.toJson());
		}
		JsonObject root = new JsonObject();
		root.add("settings", json);
		return root;
	}

	private Path path() {
		return GammaPaths.root().resolve("settings.json");
	}

	private JsonObject readJson(Path file) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			return gson.fromJson(reader, JsonObject.class);
		} catch (IOException | JsonParseException e) {
			Gamma.LOGGER.error("Failed to read {}, ignoring", file, e);
			return null;
		}
	}

	private void writeJson(Path file, JsonObject root) {
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			gson.toJson(root, writer);
		} catch (IOException e) {
			Gamma.LOGGER.error("Failed to write {}", file, e);
		}
	}
}

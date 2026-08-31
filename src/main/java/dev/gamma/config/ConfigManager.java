package dev.gamma.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.gamma.Gamma;
import dev.gamma.config.setting.Setting;
import dev.gamma.core.GammaExecutor;
import dev.gamma.core.GammaPaths;
import dev.gamma.core.Module;
import dev.gamma.core.ModuleRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * JSON module state, one profile per server address, under {@code gamma/profiles/}. Reads and
 * writes happen off the client thread via {@link GammaExecutor}; only the (cheap) JSON
 * snapshot/apply step touches module state, and that step always runs back on the client
 * thread to avoid racing ticking/rendering modules.
 */
public final class ConfigManager {

	private static final int CURRENT_VERSION = 4;
	private static final long SAVE_DEBOUNCE_MS = 500;
	private static final String PROFILE_DIR = "profiles";
	private static final String CONFIG_DIR = "configs";
	/** Key inside a config file naming the server it is bound to. */
	private static final String SERVER_KEY = "serverAddress";
	/** Example configs shipped in the jar; see {@link #installBundledConfigs()}. */
	private static final List<String> BUNDLED_CONFIGS = List.of("DonutSMP (Example)");
	private static final String BUNDLED_MARKER = ".examples_installed";

	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private volatile ScheduledFuture<?> pendingSave;

	/** Static since chunk logging (Phase 5) keys its own per-server DB files off the same identity. */
	public static String currentProfileKey(Minecraft client) {
		if (client.hasSingleplayerServer()) {
			return "singleplayer";
		}
		ServerData server = client.getCurrentServer();
		return server != null ? server.ip : "unknown";
	}

	public void load(String profileKey, ModuleRegistry registry) {
		Path file = profilePath(profileKey);
		GammaExecutor.execute(() -> {
			JsonObject root = readJson(file);
			Minecraft.getInstance().execute(() -> apply(root, registry));
		});
	}

	public void saveNow(String profileKey, ModuleRegistry registry) {
		JsonObject root = snapshot(registry);
		GammaExecutor.execute(() -> writeJson(profilePath(profileKey), root));
	}

	/** Coalesces bursts of calls (e.g. a GUI slider being dragged) into one write, {@link #SAVE_DEBOUNCE_MS} after the last one. */
	public void requestSave(String profileKey, ModuleRegistry registry) {
		JsonObject root = snapshot(registry);
		ScheduledFuture<?> previous = pendingSave;
		if (previous != null) {
			previous.cancel(false);
		}
		pendingSave = GammaExecutor.schedule(() -> writeJson(profilePath(profileKey), root), SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
	}

	public List<String> listProfiles() {
		return listJsonNames(PROFILE_DIR);
	}

	// --- Named configs -------------------------------------------------------------------------
	//
	// Profiles above are automatic and per-server: they follow you around and you never name them.
	// That is the right default, but it is also why module state appeared to be "not saved" when
	// you set things up in singleplayer and then joined a server -- the server simply has its own
	// profile, and loading it is correct behaviour that looks like data loss. Named configs are the
	// missing half: an explicit, user-named snapshot that can be loaded into any world regardless
	// of which profile is active. Same on-disk format and same snapshot/apply code, different
	// directory and a name you chose.

	/**
	 * Writes the live module state under {@code name}, overwriting any config already using it.
	 *
	 * <p>The server binding is carried across rather than being part of the snapshot. A snapshot is
	 * module state and knows nothing about server bindings, so saving over an existing config used to
	 * drop its binding on the floor — re-saving a config you had bound to a server silently unbound
	 * it, which looked exactly like the address field failing to save.
	 */
	public void saveConfig(String name, ModuleRegistry registry) {
		JsonObject root = snapshot(registry);
		GammaExecutor.execute(() -> {
			Path file = configPath(name);
			JsonObject existing = readJson(file);
			if (existing != null && existing.has(SERVER_KEY)) {
				root.add(SERVER_KEY, existing.get(SERVER_KEY));
			}
			writeJson(file, root);
		});
	}

	/**
	 * Applies a named config to every module. Nothing is written back — the loaded state becomes
	 * the live state, and the active per-server profile picks it up at its own next save, so
	 * loading a config in one world doesn't silently rewrite another world's profile.
	 */
	public void loadConfig(String name, ModuleRegistry registry) {
		Path file = configPath(name);
		GammaExecutor.execute(() -> {
			JsonObject root = readJson(file);
			Minecraft.getInstance().execute(() -> apply(root, registry));
		});
	}

	/** Reports whether the file was actually removed, so callers can tell "deleted" from "wasn't there". */
	public void deleteConfig(String name, Consumer<Boolean> onDone) {
		GammaExecutor.execute(() -> {
			boolean deleted;
			try {
				deleted = Files.deleteIfExists(configPath(name));
			} catch (IOException e) {
				Gamma.LOGGER.error("Failed to delete config '{}'", name, e);
				deleted = false;
			}
			boolean result = deleted;
			Minecraft.getInstance().execute(() -> onDone.accept(result));
		});
	}

	// --- Bundled example configs ---------------------------------------------------------------

	/**
	 * Example configs shipped inside the jar, copied into {@code gamma/configs/} on first run so a
	 * new install has something to look at rather than an empty list.
	 *
	 * <p>Guarded by a marker file rather than by the presence of the config itself. Checking only
	 * whether the file exists would reinstall an example the user had deliberately deleted, on every
	 * single launch, which is the kind of thing that makes people stop trusting a mod's config
	 * directory. The marker says "this install has already been offered these", which is the actual
	 * question.
	 *
	 * <p>Runs on the executor: it is file I/O, and the project conventions keeps that off the client thread.
	 */
	public void installBundledConfigs() {
		GammaExecutor.execute(() -> {
			Path marker = GammaPaths.root().resolve(BUNDLED_MARKER);
			if (Files.exists(marker)) {
				return;
			}
			for (String name : BUNDLED_CONFIGS) {
				installBundledConfig(name);
			}
			try {
				Files.writeString(marker, "Example configs have been installed once. Delete this file to get them back.", StandardCharsets.UTF_8);
			} catch (IOException e) {
				// Non-fatal: the worst case is the examples being offered again next launch.
				Gamma.LOGGER.warn("Could not write the example-config marker", e);
			}
		});
	}

	private void installBundledConfig(String name) {
		Path target = configPath(name);
		if (Files.exists(target)) {
			return;
		}
		String resource = "/assets/gamma/configs/" + name + ".json";
		try (InputStream in = ConfigManager.class.getResourceAsStream(resource)) {
			if (in == null) {
				Gamma.LOGGER.warn("Example config '{}' is missing from the jar at {}", name, resource);
				return;
			}
			Files.copy(in, target);
			Gamma.LOGGER.info("Installed example config '{}'", name);
		} catch (IOException e) {
			Gamma.LOGGER.error("Failed to install example config '{}'", name, e);
		}
	}

	public List<String> listConfigs() {
		return listJsonNames(CONFIG_DIR);
	}

	// --- Server bindings -----------------------------------------------------------------------
	//
	// A config can name a server address, and joining that server loads it instead of the automatic
	// per-server profile. The binding is stored inside the config file rather than in a separate
	// index, so a config is one self-contained file: copying it somewhere takes its binding with it,
	// and deleting it cannot leave an orphaned entry pointing at nothing.

	/** The server address this config is bound to, or empty if it isn't bound. Blocking; call off-thread. */
	public String configServer(String name) {
		JsonObject root = readJson(configPath(name));
		return root != null && root.has(SERVER_KEY) ? root.get(SERVER_KEY).getAsString() : "";
	}

	/**
	 * Binds (or, with a blank address, unbinds) a config to a server.
	 *
	 * <p>Read-modify-write rather than a fresh snapshot: the point is to change the binding without
	 * touching the module state the config was saved with.
	 */
	public void setConfigServer(String name, String serverAddress, Runnable onDone) {
		GammaExecutor.execute(() -> {
			Path file = configPath(name);
			JsonObject root = readJson(file);
			if (root == null) {
				Gamma.LOGGER.warn("Cannot bind a server to config '{}': it has no file", name);
				return;
			}
			String trimmed = serverAddress == null ? "" : serverAddress.trim();
			if (trimmed.isEmpty()) {
				root.remove(SERVER_KEY);
			} else {
				root.addProperty(SERVER_KEY, trimmed);
			}
			writeJson(file, root);
			if (onDone != null) {
				Minecraft.getInstance().execute(onDone);
			}
		});
	}

	/**
	 * Loads whatever should be active for {@code profileKey}: a config bound to that address if one
	 * claims it, otherwise the automatic profile.
	 *
	 * <p>Matching is case-insensitive and ignores a {@code :25565} suffix on either side, because the
	 * address you typed into the server list and the one Minecraft reports are routinely written
	 * differently for the same server.
	 */
	public void loadForServer(String profileKey, ModuleRegistry registry) {
		GammaExecutor.execute(() -> {
			String boundConfig = null;
			for (String name : listJsonNames(CONFIG_DIR)) {
				if (addressesMatch(configServer(name), profileKey)) {
					boundConfig = name;
					break;
				}
			}
			Path file = boundConfig == null ? profilePath(profileKey) : configPath(boundConfig);
			String loaded = boundConfig;
			JsonObject root = readJson(file);
			Minecraft.getInstance().execute(() -> {
				apply(root, registry);
				if (loaded != null) {
					Gamma.LOGGER.info("Loaded config '{}' for server {}", loaded, profileKey);
				}
			});
		});
	}

	private static boolean addressesMatch(String a, String b) {
		return !a.isEmpty() && normalizeAddress(a).equals(normalizeAddress(b));
	}

	private static String normalizeAddress(String address) {
		String lower = address.trim().toLowerCase(java.util.Locale.ROOT);
		return lower.endsWith(":25565") ? lower.substring(0, lower.length() - ":25565".length()) : lower;
	}

	/**
	 * Directory listings are blocking I/O, which the project conventions keeps off the client thread — so the GUI
	 * asks for the list this way and repaints when it arrives, rather than stat-ing the directory
	 * every frame. {@code onDone} runs back on the client thread.
	 */
	public void listConfigsAsync(Consumer<List<String>> onDone) {
		GammaExecutor.execute(() -> {
			List<String> names = listJsonNames(CONFIG_DIR);
			Minecraft.getInstance().execute(() -> onDone.accept(names));
		});
	}

	/** Rejects names that would not survive the round trip through {@link GammaPaths#sanitizeFileName}. */
	public static boolean isValidConfigName(String name) {
		String trimmed = name.trim();
		return !trimmed.isEmpty() && trimmed.length() <= 48 && GammaPaths.sanitizeFileName(trimmed).equals(trimmed);
	}

	private List<String> listJsonNames(String dir) {
		try (Stream<Path> files = Files.list(GammaPaths.dir(dir))) {
			return files.map(path -> path.getFileName().toString())
					.filter(name -> name.endsWith(".json"))
					.map(name -> name.substring(0, name.length() - ".json".length()))
					.sorted()
					.toList();
		} catch (IOException e) {
			Gamma.LOGGER.error("Failed to list {}", dir, e);
			return List.of();
		}
	}

	private Path profilePath(String profileKey) {
		return GammaPaths.dir(PROFILE_DIR).resolve(GammaPaths.sanitizeFileName(profileKey) + ".json");
	}

	private Path configPath(String name) {
		return GammaPaths.dir(CONFIG_DIR).resolve(GammaPaths.sanitizeFileName(name.trim()) + ".json");
	}

	private JsonObject snapshot(ModuleRegistry registry) {
		JsonObject root = new JsonObject();
		root.addProperty("version", CURRENT_VERSION);
		JsonObject modules = new JsonObject();
		for (Module module : registry.all()) {
			JsonObject moduleJson = new JsonObject();
			moduleJson.addProperty("enabled", module.isEnabled());
			JsonObject settingsJson = new JsonObject();
			for (Setting<?> setting : module.settings()) {
				settingsJson.add(setting.name(), setting.toJson());
			}
			moduleJson.add("settings", settingsJson);
			modules.add(module.name(), moduleJson);
		}
		root.add("modules", modules);
		return root;
	}

	private void apply(JsonObject root, ModuleRegistry registry) {
		if (root == null) {
			return; // No profile on disk yet — modules keep their compiled-in defaults.
		}
		int version = root.has("version") ? root.get("version").getAsInt() : 0;
		JsonObject migrated = migrate(root, version);
		if (!migrated.has("modules")) {
			return;
		}
		JsonObject modules = migrated.getAsJsonObject("modules");
		for (String moduleName : modules.keySet()) {
			registry.get(moduleName).ifPresentOrElse(
					module -> applyModule(module, modules.getAsJsonObject(moduleName)),
					() -> Gamma.LOGGER.warn("Config profile references unknown module '{}', skipping", moduleName));
		}
	}

	private void applyModule(Module module, JsonObject moduleJson) {
		if (moduleJson.has("settings")) {
			JsonObject settingsJson = moduleJson.getAsJsonObject("settings");
			for (Setting<?> setting : module.settings()) {
				if (!settingsJson.has(setting.name())) {
					continue;
				}
				try {
					setting.fromJson(settingsJson.get(setting.name()));
				} catch (RuntimeException e) {
					Gamma.LOGGER.warn("Failed to load setting '{}' for module '{}', keeping default", setting.name(), module.name(), e);
				}
			}
		}
		module.setEnabled(moduleJson.has("enabled") && moduleJson.get("enabled").getAsBoolean());
	}

	/**
	 * Version-tagged migration hook.
	 *
	 * <p>Modules are keyed by {@link Module#name()}, so renaming one orphans everything a player
	 * had saved under the old name — {@link #apply} would log "unknown module" and silently leave
	 * that module at its compiled-in defaults, across every server profile at once. Renames
	 * therefore bump the version and get an entry here.
	 *
	 * <p>v1 -&gt; v2: {@code ItemExpiry} became {@code ShardItemTimer} when it moved into the
	 * DonutSMP category.
	 */
	private JsonObject migrate(JsonObject root, int fromVersion) {
		if (fromVersion > CURRENT_VERSION) {
			Gamma.LOGGER.warn("Config profile is from a newer Gamma version ({} > {}), loading best-effort", fromVersion, CURRENT_VERSION);
		}
		if (fromVersion < 2) {
			renameModule(root, "ItemExpiry", "ShardItemTimer");
		}
		if (fromVersion < 3) {
			dropModule(root, "Search");
		}
		if (fromVersion < 4) {
			renameModule(root, "ItemFrameESP", "ElytraFinder");
		}
		return root;
	}

	/**
	 * Forgets a module that no longer exists. Without this, {@link #apply} warns about the stale key
	 * on every world load for the life of the profile — the block is harmless, but a log line that
	 * recurs forever and describes nothing actionable trains you to ignore the log.
	 */
	private void dropModule(JsonObject root, String name) {
		if (!root.has("modules")) {
			return;
		}
		JsonObject modules = root.getAsJsonObject("modules");
		if (modules.remove(name) != null) {
			Gamma.LOGGER.info("Config migration: dropped settings for removed module '{}'", name);
		}
	}

	/** Moves a saved module's block to a new key, leaving an existing block under the new name alone. */
	private void renameModule(JsonObject root, String from, String to) {
		if (!root.has("modules")) {
			return;
		}
		JsonObject modules = root.getAsJsonObject("modules");
		if (!modules.has(from) || modules.has(to)) {
			return;
		}
		modules.add(to, modules.get(from));
		modules.remove(from);
		Gamma.LOGGER.info("Config migration: module '{}' renamed to '{}'", from, to);
	}

	private JsonObject readJson(Path file) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			return gson.fromJson(reader, JsonObject.class);
		} catch (IOException | JsonParseException e) {
			Gamma.LOGGER.error("Failed to read config profile {}, ignoring", file, e);
			return null;
		}
	}

	private void writeJson(Path file, JsonObject root) {
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			gson.toJson(root, writer);
		} catch (IOException e) {
			Gamma.LOGGER.error("Failed to write config profile {}", file, e);
		}
	}
}

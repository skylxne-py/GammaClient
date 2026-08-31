package dev.gamma;

import dev.gamma.account.AccountManager;
import dev.gamma.chunks.ChunkObservationCollector;
import dev.gamma.command.GammaCommands;
import dev.gamma.config.ConfigManager;
import dev.gamma.config.GammaSettings;
import dev.gamma.core.GammaExecutor;
import dev.gamma.core.ModuleRegistry;
import dev.gamma.core.event.FabricEventBridge;
import dev.gamma.core.event.events.WorldLoadEvent;
import dev.gamma.core.event.events.WorldUnloadEvent;
import dev.gamma.core.keybind.KeybindManager;
import dev.gamma.gui.TitleScreenLogo;
import dev.gamma.gui.account.AccountButtons;
import dev.gamma.gui.clickgui.ClickGuiOpener;
import dev.gamma.gui.clickgui.GuiConfig;
import dev.gamma.gui.clickgui.Theme;
import dev.gamma.modules.misc.Spotify;
import dev.gamma.gui.hud.HudManager;
import dev.gamma.gui.hud.Notifications;
import dev.gamma.render.SmoketestRenderer;
import dev.gamma.render.backend.BackendProbe;
import dev.gamma.waypoints.WaypointStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.util.concurrent.TimeUnit;

public final class GammaClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		String version = FabricLoader.getInstance()
				.getModContainer(Gamma.MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");

		Gamma.LOGGER.info("Gamma {} initializing", version);

		// BackendProbe.activeBackend() calls RenderSystem.getDevice(), which isn't ready yet at
		// this point in the Minecraft constructor -- the client entrypoint fires before the render
		// device/window exist. CLIENT_STARTED fires once the client is fully up.
		ClientLifecycleEvents.CLIENT_STARTED.register(client ->
				Gamma.LOGGER.info("Gamma {} render backend: {}", version, BackendProbe.activeBackend()));

		ModuleRegistry registry = new ModuleRegistry();
		registry.registerAll();

		ConfigManager configManager = new ConfigManager();
		configManager.installBundledConfigs();
		KeybindManager keybindManager = new KeybindManager(registry, Gamma.EVENT_BUS);
		SmoketestRenderer smoketestRenderer = new SmoketestRenderer();
		smoketestRenderer.install();

		HudManager hudManager = new HudManager(registry, Gamma.EVENT_BUS);
		hudManager.load();
		hudManager.install();

		// The Spotify overlay is a HUD element driven by a module, so the two have to be
		// introduced: the module needs the manager to resolve its element's live position and
		// scale for click hit-testing, and this is the first point at which both exist.
		if (Spotify.instance != null) {
			Spotify.instance.attach(hudManager);
		}

		// Client-wide preferences (chat notifications, menu key, HUD master switch, blocklist bypass).
		// Constructed before anything that reads them so the static accessors have an instance to
		// answer from; until load() lands they answer with the compiled-in defaults, which is right.
		GammaSettings settings = new GammaSettings();
		settings.load();

		// Reads GammaSettings.titleScreenLogo() at draw time, so it has to come after the instance
		// exists. The registration itself is once-for-the-game, like the Spotify screen hook.
		TitleScreenLogo.install();

		// Accounts, and the buttons that reach them. After GammaSettings for the same reason as the
		// logo: the login reads MsaClientId out of it. load() only reads the stored list -- nothing
		// authenticates, and no token is touched, until the user picks an account.
		AccountManager accountManager = new AccountManager();
		accountManager.load();
		AccountButtons.install();

		Theme theme = new Theme();
		GuiConfig guiConfig = new GuiConfig();
		guiConfig.load(theme);
		ClickGuiOpener clickGuiOpener = new ClickGuiOpener(registry, theme, guiConfig, hudManager, configManager, settings, Gamma.EVENT_BUS);

		// always-on chunk logging, independent of whether NewChunks (the rendering
		// module) is toggled on; see ChunkObservationCollector's own doc comment.
		ChunkObservationCollector chunkObservationCollector = new ChunkObservationCollector(Gamma.EVENT_BUS);
		chunkObservationCollector.install();

		// waypoints are an always-on core service too, same reasoning as chunk logging:
		// they must persist and render regardless of which module happens to be toggled.
		WaypointStore waypointStore = new WaypointStore();
		waypointStore.install(Gamma.EVENT_BUS);

		new GammaCommands(registry, configManager, keybindManager, smoketestRenderer, clickGuiOpener, hudManager, chunkObservationCollector, waypointStore).install();
		new FabricEventBridge(Gamma.EVENT_BUS).install();

		// Profiles are per-server: load module state as each world/server becomes active, save
		// it as each one stops being active. By unload time Minecraft's own connection state
		// may already point at the *next* server, so the key is captured at load and reused at
		// unload rather than re-derived — otherwise a server switch could save to the wrong file.
		String[] activeProfileKey = {null};
		Gamma.EVENT_BUS.subscribe(WorldLoadEvent.class, event -> {
			String key = ConfigManager.currentProfileKey(Minecraft.getInstance());
			activeProfileKey[0] = key;
			// Not load(): a config bound to this server takes priority over the automatic profile.
			configManager.loadForServer(key, registry);
		});
		Gamma.EVENT_BUS.subscribe(WorldUnloadEvent.class, event -> {
			// Whatever was on screen was about a world that is now gone, and a notification that
			// outlives its world would arrive on the next one claiming to be about it.
			Notifications.clear();
			if (activeProfileKey[0] != null) {
				// saveNow, not requestSave: the debounced variant fires 500ms later, and quitting
				// the game straight from the disconnect screen shuts the executor down inside that
				// window -- so the last thing you changed before leaving a server was the thing
				// most likely to be lost.
				configManager.saveNow(activeProfileKey[0], registry);
			}
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			// Re-deriving the key here asks Minecraft which server we're on while it is being torn
			// down, which answers "unknown" -- and the live module state then landed in
			// unknown.json instead of the profile it came from. The key captured at world load is
			// the only one that's still true at this point.
			String key = activeProfileKey[0] != null ? activeProfileKey[0] : ConfigManager.currentProfileKey(client);
			configManager.saveNow(key, registry);
			settings.saveNow();
			waypointStore.saveNow();
			GammaExecutor.shutdownAndAwait(5, TimeUnit.SECONDS);
		});
	}
}

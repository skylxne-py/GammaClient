package dev.gamma.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.gamma.chunks.ChunkObservationCollector;
import dev.gamma.chunks.FixtureRecorder;
import dev.gamma.chunks.StashScorer;
import dev.gamma.chunks.db.ChunkDatabase;
import dev.gamma.chunks.model.ChunkQuery;
import dev.gamma.chunks.model.ChunkRecord;
import dev.gamma.chunks.model.StashScore;
import dev.gamma.chunks.model.StashWeights;
import dev.gamma.config.ConfigManager;
import dev.gamma.config.setting.KeybindSetting;
import dev.gamma.core.Module;
import dev.gamma.core.ModuleProfiler;
import dev.gamma.core.ModuleRegistry;
import dev.gamma.core.keybind.KeybindManager;
import dev.gamma.gui.clickgui.ClickGuiOpener;
import dev.gamma.gui.hud.HudEditorScreen;
import dev.gamma.gui.hud.HudManager;
import dev.gamma.gui.map.MapOverlayScreen;
import dev.gamma.modules.misc.Spotify;
import dev.gamma.modules.world.StashFinder;
import dev.gamma.render.SmoketestRenderer;
import dev.gamma.util.ItemDump;
import dev.gamma.util.KeyNames;
import dev.gamma.waypoints.Waypoint;
import dev.gamma.waypoints.WaypointCategory;
import dev.gamma.waypoints.WaypointImportExport;
import dev.gamma.waypoints.WaypointSource;
import dev.gamma.waypoints.WaypointStore;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Brigadier commands, invoked with a {@code .} prefix instead of vanilla's {@code /}: chat
 * messages starting with {@code .} are intercepted before send and redirected into the same
 * client command dispatcher a normal {@code /} command would use (see {@link #install}).
 */
public final class GammaCommands {

	private final ModuleRegistry registry;
	private final ConfigManager configManager;
	private final KeybindManager keybindManager;
	private final SmoketestRenderer smoketestRenderer;
	private final ClickGuiOpener clickGuiOpener;
	private final HudManager hudManager;
	private final ChunkObservationCollector chunkObservationCollector;
	private final WaypointStore waypointStore;

	private boolean recordingFixtures;

	public GammaCommands(ModuleRegistry registry, ConfigManager configManager, KeybindManager keybindManager,
			SmoketestRenderer smoketestRenderer, ClickGuiOpener clickGuiOpener, HudManager hudManager,
			ChunkObservationCollector chunkObservationCollector, WaypointStore waypointStore) {
		this.registry = registry;
		this.configManager = configManager;
		this.keybindManager = keybindManager;
		this.smoketestRenderer = smoketestRenderer;
		this.clickGuiOpener = clickGuiOpener;
		this.hudManager = hudManager;
		this.chunkObservationCollector = chunkObservationCollector;
		this.waypointStore = waypointStore;
	}

	public void install() {
		ClientCommandRegistrationCallback.EVENT.register(this::register);
		ClientSendMessageEvents.ALLOW_CHAT.register(this::interceptChat);
	}

	private boolean interceptChat(String message) {
		if (message.length() < 2 || message.charAt(0) != '.') {
			return true;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player != null && client.player.connection != null) {
			client.player.connection.sendCommand(message.substring(1));
		}
		return false;
	}

	private void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext registryAccess) {
		dispatcher.register(literal("help").executes(context -> {
			context.getSource().sendFeedback(Component.literal("Gamma commands: .help, .toggle <module>, .bind <module> <key>, .profile <load|save|list>, .config <save|load|delete> <name>, .config list, .gamma smoketest, .gamma gui, .gamma hud, .gamma map, .gamma iteminfo, .gamma profile [reset], .chunks stats, .chunks query [minStorage] [dimension], .chunks stashes [count], .chunks record, .chunks compact, .waypoints add <name>, .waypoints addcat <category> <name>, .waypoints remove <name>, .waypoints list, .waypoints export <path>, .waypoints import <xaero|lunar|json> <path>, .spotify <connect|disconnect|status|devices|diagnose>, .spotify play <device>"));
			return 1;
		}));

		dispatcher.register(literal("toggle")
				.then(argument("module", word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(registry.all().stream().map(Module::name), builder))
						.executes(this::runToggle)));

		dispatcher.register(literal("bind")
				.then(argument("module", word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(registry.all().stream().map(Module::name), builder))
						.then(argument("key", word())
								.executes(this::runBind))));

		dispatcher.register(literal("profile")
				.then(literal("save").executes(this::runProfileSave))
				.then(literal("load").executes(this::runProfileLoad))
				.then(literal("list").executes(this::runProfileList)));

		// Named configs, the command-line half of the ClickGUI's Configs screen. Separate verb from
		// `profile` on purpose: a profile is the automatic per-server one and takes no name, a
		// config is an explicit snapshot you named and can load anywhere.
		dispatcher.register(literal("config")
				.then(literal("save")
						.then(argument("name", word()).executes(this::runConfigSave)))
				.then(literal("load")
						.then(argument("name", word())
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(configManager.listConfigs(), builder))
								.executes(this::runConfigLoad)))
				.then(literal("delete")
						.requires(FabricClientCommandSource::attended)
						.then(argument("name", word())
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(configManager.listConfigs(), builder))
								.executes(this::runConfigDelete)))
				.then(literal("list").executes(this::runConfigList)));

		dispatcher.register(literal("gamma")
				.then(literal("smoketest").executes(this::runSmoketest))
				.then(literal("gui").executes(this::runGui))
				.then(literal("hud").executes(this::runHud))
				.then(literal("map").executes(this::runMap))
				.then(literal("iteminfo").executes(this::runItemInfo))
				.then(literal("profile").executes(this::runGammaProfile)
						.then(literal("reset").executes(this::runGammaProfileReset))));

		dispatcher.register(literal("chunks")
				.then(literal("stats").executes(this::runChunksStats))
				.then(literal("record").executes(this::runChunksRecord))
				.then(literal("query").executes(this::runChunksQuery)
						.then(argument("minStorage", integer(0)).executes(this::runChunksQueryMinStorage)
								.then(argument("dimension", word()).executes(this::runChunksQueryMinStorageDimension))))
				.then(literal("stashes").executes(this::runChunksStashes)
						.then(argument("count", integer(1, 100)).executes(this::runChunksStashesCount)))
				.then(literal("compact").requires(FabricClientCommandSource::attended).executes(this::runChunksCompact)));

		// `connect` is attended-only: it opens a browser window, which is not something to do to
		// someone who isn't sitting there.
		dispatcher.register(literal("spotify")
				.then(literal("connect").requires(FabricClientCommandSource::attended).executes(this::runSpotifyConnect))
				.then(literal("disconnect").executes(this::runSpotifyDisconnect))
				.then(literal("status").executes(this::runSpotifyStatus))
				.then(literal("devices").executes(this::runSpotifyDevices))
				.then(literal("diagnose").executes(this::runSpotifyDiagnose))
				.then(literal("play").then(argument("device", greedyString()).executes(this::runSpotifyPlayOn))));

		dispatcher.register(literal("waypoints")
				.then(literal("add").then(argument("name", greedyString()).executes(this::runWaypointAdd)))
				.then(literal("addcat").then(argument("category", word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(
								Arrays.stream(WaypointCategory.values()).map(c -> c.name().toLowerCase(Locale.ROOT)), builder))
						.then(argument("name", greedyString()).executes(this::runWaypointAddCategory))))
				.then(literal("remove").then(argument("name", greedyString()).executes(this::runWaypointRemove)))
				.then(literal("list").executes(this::runWaypointList))
				.then(literal("export").then(argument("path", greedyString()).executes(this::runWaypointExport)))
				.then(literal("import")
						.then(literal("xaero").then(argument("path", greedyString()).executes(this::runWaypointImportXaero)))
						.then(literal("lunar").then(argument("path", greedyString()).executes(this::runWaypointImportLunar)))
						.then(literal("json").then(argument("path", greedyString()).executes(this::runWaypointImportJson)))));
	}

	private int runGui(CommandContext<FabricClientCommandSource> context) {
		clickGuiOpener.open();
		return 1;
	}

	private int runHud(CommandContext<FabricClientCommandSource> context) {
		Minecraft.getInstance().gui.setScreen(new HudEditorScreen(hudManager));
		return 1;
	}

	private int runSmoketest(CommandContext<FabricClientCommandSource> context) {
		smoketestRenderer.toggle();
		context.getSource().sendFeedback(Component.literal(
				"Smoketest " + (smoketestRenderer.isActive() ? "enabled" : "disabled") + " (near world spawn)"));
		return 1;
	}

	private int runSpotifyConnect(CommandContext<FabricClientCommandSource> context) {
		return withSpotify(context, module -> {
			module.commandLogin();
			return 1;
		});
	}

	private int runSpotifyDisconnect(CommandContext<FabricClientCommandSource> context) {
		return withSpotify(context, module -> {
			module.commandLogout();
			return 1;
		});
	}

	private int runSpotifyStatus(CommandContext<FabricClientCommandSource> context) {
		return withSpotify(context, module -> {
			context.getSource().sendFeedback(Component.literal(module.statusLine()));
			return 1;
		});
	}

	private int runSpotifyDiagnose(CommandContext<FabricClientCommandSource> context) {
		return withSpotify(context, module -> {
			module.commandDiagnose();
			return 1;
		});
	}

	private int runSpotifyDevices(CommandContext<FabricClientCommandSource> context) {
		return withSpotify(context, module -> {
			module.commandDevices();
			return 1;
		});
	}

	private int runSpotifyPlayOn(CommandContext<FabricClientCommandSource> context) {
		return withSpotify(context, module -> {
			module.commandPlayOn(getString(context, "device"));
			return 1;
		});
	}

	private int withSpotify(CommandContext<FabricClientCommandSource> context, java.util.function.ToIntFunction<Spotify> action) {
		Spotify module = Spotify.instance;
		if (module == null) {
			context.getSource().sendError(Component.literal("The Spotify module isn't loaded"));
			return 0;
		}
		return action.applyAsInt(module);
	}

	private int runToggle(CommandContext<FabricClientCommandSource> context) {
		String name = getString(context, "module");
		FabricClientCommandSource source = context.getSource();
		registry.get(name).ifPresentOrElse(module -> {
			module.toggle();
			source.sendFeedback(Component.literal(module.name() + " " + (module.isEnabled() ? "enabled" : "disabled")));
		}, () -> source.sendError(Component.literal("No module named '" + name + "'")));
		return 1;
	}

	private int runBind(CommandContext<FabricClientCommandSource> context) {
		String moduleName = getString(context, "module");
		String keyName = getString(context, "key");
		FabricClientCommandSource source = context.getSource();

		registry.get(moduleName).ifPresentOrElse(module -> {
			OptionalInt keyCode = KeyNames.parse(keyName);
			if (keyCode.isEmpty()) {
				source.sendError(Component.literal("Unknown key '" + keyName + "'"));
				return;
			}
			KeybindSetting.Bind previousBind = module.keybind().get();
			module.keybind().set(new KeybindSetting.Bind(keyCode.getAsInt(), previousBind.mode()));
			source.sendFeedback(Component.literal(module.name() + " bound to " + KeyNames.name(keyCode.getAsInt())));
			keybindManager.conflictsFor(module).forEach(conflict ->
					source.sendFeedback(Component.literal("Warning: also bound to " + conflict.name())));
		}, () -> source.sendError(Component.literal("No module named '" + moduleName + "'")));
		return 1;
	}

	private int runProfileSave(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String key = ConfigManager.currentProfileKey(source.getClient());
		configManager.saveNow(key, registry);
		source.sendFeedback(Component.literal("Saved profile '" + key + "'"));
		return 1;
	}

	private int runProfileLoad(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String key = ConfigManager.currentProfileKey(source.getClient());
		configManager.load(key, registry);
		source.sendFeedback(Component.literal("Loading profile '" + key + "'"));
		return 1;
	}

	private int runProfileList(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		var profiles = configManager.listProfiles();
		source.sendFeedback(Component.literal(profiles.isEmpty() ? "No saved profiles" : "Profiles: " + String.join(", ", profiles)));
		return 1;
	}

	private int runConfigSave(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String name = context.getArgument("name", String.class);
		if (!ConfigManager.isValidConfigName(name)) {
			source.sendError(Component.literal("Invalid config name '" + name + "' — letters, digits, - and _ only, up to 48 characters"));
			return 0;
		}
		configManager.saveConfig(name, registry);
		source.sendFeedback(Component.literal("Saved config '" + name + "'"));
		return 1;
	}

	private int runConfigLoad(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String name = context.getArgument("name", String.class);
		configManager.loadConfig(name, registry);
		source.sendFeedback(Component.literal("Loading config '" + name + "'"));
		return 1;
	}

	private int runConfigDelete(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String name = context.getArgument("name", String.class);
		configManager.deleteConfig(name, deleted -> {
			if (deleted) {
				source.sendFeedback(Component.literal("Deleted config '" + name + "'"));
			} else {
				source.sendError(Component.literal("No config named '" + name + "'"));
			}
		});
		return 1;
	}

	private int runConfigList(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		var configs = configManager.listConfigs();
		source.sendFeedback(Component.literal(configs.isEmpty() ? "No saved configs" : "Configs: " + String.join(", ", configs)));
		return 1;
	}

	private int runChunksStats(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		ChunkDatabase database = chunkObservationCollector.database();
		if (database == null) {
			source.sendError(Component.literal("No chunk database open — connect to a world first."));
			return 0;
		}
		database.stats(stats -> Minecraft.getInstance().execute(() -> source.sendFeedback(Component.literal(
				("Chunks logged: %d (new: %d, existing: %d, unknown: %d) — storage blocks: %d, avg confidence: %.2f")
						.formatted(stats.totalChunks(), stats.likelyNew(), stats.likelyExisting(), stats.unknown(),
								stats.totalStorageBlocks(), stats.averageConfidence())))));
		return 1;
	}

	private int runChunksQuery(CommandContext<FabricClientCommandSource> context) {
		return runChunksQuery(context, null, null);
	}

	private int runChunksQueryMinStorage(CommandContext<FabricClientCommandSource> context) {
		return runChunksQuery(context, getInteger(context, "minStorage"), null);
	}

	private int runChunksQueryMinStorageDimension(CommandContext<FabricClientCommandSource> context) {
		return runChunksQuery(context, getInteger(context, "minStorage"), getString(context, "dimension"));
	}

	private int runChunksQuery(CommandContext<FabricClientCommandSource> context, Integer minStorage, String dimension) {
		FabricClientCommandSource source = context.getSource();
		ChunkDatabase database = chunkObservationCollector.database();
		if (database == null) {
			source.sendError(Component.literal("No chunk database open — connect to a world first."));
			return 0;
		}
		ChunkQuery query = new ChunkQuery(dimension, null, null, null, null, minStorage, null, null, 10);
		database.query(query, results -> Minecraft.getInstance().execute(() -> {
			if (results.isEmpty()) {
				source.sendFeedback(Component.literal("No chunks matched."));
				return;
			}
			for (ChunkRecord record : results) {
				source.sendFeedback(Component.literal("(%d, %d) in %s — %s, storage=%d, confidence=%.2f".formatted(
						record.x(), record.z(), record.dimension(), record.classification(), record.storageCount(), record.confidence())));
			}
		}));
		return 1;
	}

	private int runChunksCompact(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		ChunkDatabase database = chunkObservationCollector.database();
		if (database == null) {
			source.sendError(Component.literal("No chunk database open — connect to a world first."));
			return 0;
		}
		source.sendFeedback(Component.literal("Compacting chunk database — this can take a while on a large database..."));
		database.compact(() -> Minecraft.getInstance().execute(() ->
				source.sendFeedback(Component.literal("Chunk database compaction complete."))));
		return 1;
	}

	private int runChunksRecord(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		if (recordingFixtures) {
			chunkObservationCollector.setFixtureRecorder(null);
			recordingFixtures = false;
			source.sendFeedback(Component.literal("Chunk fixture recording stopped."));
		} else {
			String server = ConfigManager.currentProfileKey(source.getClient());
			chunkObservationCollector.setFixtureRecorder(new FixtureRecorder(server));
			recordingFixtures = true;
			source.sendFeedback(Component.literal("Chunk fixture recording started — dumping to gamma/chunks/fixtures/" + server));
		}
		return 1;
	}

	private int runMap(CommandContext<FabricClientCommandSource> context) {
		Minecraft.getInstance().gui.setScreen(new MapOverlayScreen());
		return 1;
	}

	private static final int PROFILE_ROWS_SHOWN = 15;

	private int runGammaProfile(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		List<ModuleProfiler.Snapshot> snapshot = ModuleProfiler.snapshot();
		if (snapshot.isEmpty()) {
			source.sendFeedback(Component.literal("No extraction/tick timing recorded yet — enable some modules and move around first."));
			return 1;
		}
		source.sendFeedback(Component.literal("Module timing, sorted by avg (top " + PROFILE_ROWS_SHOWN + "):"));
		snapshot.stream().limit(PROFILE_ROWS_SHOWN).forEach(s ->
				source.sendFeedback(Component.literal("%s#%s — avg %.1fus, max %.1fus, n=%d".formatted(
						s.moduleName(), s.eventName(), s.avgMicros(), s.maxMicros(), s.samples()))));
		return 1;
	}

	private int runGammaProfileReset(CommandContext<FabricClientCommandSource> context) {
		ModuleProfiler.reset();
		context.getSource().sendFeedback(Component.literal("Module timing reset."));
		return 1;
	}

	// -- .chunks stashes ------------------------------------------------------

	private int runChunksStashes(CommandContext<FabricClientCommandSource> context) {
		return runChunksStashes(context, 10);
	}

	private int runChunksStashesCount(CommandContext<FabricClientCommandSource> context) {
		return runChunksStashes(context, getInteger(context, "count"));
	}

	private int runChunksStashes(CommandContext<FabricClientCommandSource> context, int count) {
		FabricClientCommandSource source = context.getSource();
		ChunkDatabase database = chunkObservationCollector.database();
		if (database == null) {
			source.sendError(Component.literal("No chunk database open — connect to a world first."));
			return 0;
		}
		ClientLevel level = Minecraft.getInstance().level;
		String dimension = level != null ? level.dimension().identifier().toString() : null;
		StashFinder stashFinder = StashFinder.instance;
		StashWeights weights = stashFinder != null ? stashFinder.weights() : StashWeights.DEFAULTS;

		ChunkQuery query = new ChunkQuery(dimension, null, null, null, null, 1, null, null, 500);
		database.query(query, candidates -> Minecraft.getInstance().execute(() -> {
			if (candidates.isEmpty()) {
				source.sendFeedback(Component.literal("No storage-containing chunks logged yet."));
				return;
			}
			// Named up front because the score is meaningless without knowing what fed it, and the
			// counted set is a per-user choice rather than a constant.
			source.sendFeedback(Component.literal("Counting: " + String.join(", ", weights.countedTypes().stream().sorted().toList())));
			List<StashScore> scored = StashScorer.score(candidates, weights);
			scored.stream().limit(count).forEach(s ->
					source.sendFeedback(Component.literal("(%d, %d) in %s — score %.2f (density %.2f, clustering %.2f, proximity %.2f), counted=%d of %d".formatted(
							s.chunk().x(), s.chunk().z(), s.chunk().dimension(), s.total(), s.density(), s.clustering(), s.proximity(),
							weights.countedStorage(s.chunk()), s.chunk().storageCount()))));
		}));
		return 1;
	}

	// -- .waypoints -----------------------------------------------------------

	private int runWaypointAdd(CommandContext<FabricClientCommandSource> context) {
		return createWaypoint(context, getString(context, "name"), WaypointCategory.MISC);
	}

	private int runWaypointAddCategory(CommandContext<FabricClientCommandSource> context) {
		WaypointCategory category = parseCategory(getString(context, "category"));
		FabricClientCommandSource source = context.getSource();
		if (category == null) {
			source.sendError(Component.literal("Unknown category '" + getString(context, "category") + "'"));
			return 0;
		}
		return createWaypoint(context, getString(context, "name"), category);
	}

	private int createWaypoint(CommandContext<FabricClientCommandSource> context, String name, WaypointCategory category) {
		FabricClientCommandSource source = context.getSource();
		Minecraft client = source.getClient();
		if (client.player == null || client.level == null) {
			source.sendError(Component.literal("No player in world."));
			return 0;
		}
		String dimension = client.level.dimension().identifier().toString();
		Waypoint waypoint = new Waypoint(WaypointStore.newId(), name, category, dimension,
				client.player.getX(), client.player.getY(), client.player.getZ(),
				null, null, true, true, System.currentTimeMillis(), WaypointSource.COMMAND);
		waypointStore.add(waypoint);
		source.sendFeedback(Component.literal("Added waypoint '" + name + "' (" + category + ") at " + Math.round(waypoint.x()) + ", " + Math.round(waypoint.y()) + ", " + Math.round(waypoint.z())));
		return 1;
	}

	private int runWaypointRemove(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String name = getString(context, "name");
		if (waypointStore.remove(name)) {
			source.sendFeedback(Component.literal("Removed waypoint '" + name + "'"));
		} else {
			source.sendError(Component.literal("No waypoint named '" + name + "'"));
		}
		return 1;
	}

	private int runWaypointList(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		List<Waypoint> all = waypointStore.all();
		if (all.isEmpty()) {
			source.sendFeedback(Component.literal("No waypoints saved for this server."));
			return 1;
		}
		for (Waypoint w : all) {
			source.sendFeedback(Component.literal("%s [%s] in %s at (%.0f, %.0f, %.0f)".formatted(
					w.name(), w.category(), w.dimension(), w.x(), w.y(), w.z())));
		}
		return 1;
	}

	private int runWaypointExport(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		Path path = Paths.get(getString(context, "path"));
		waypointStore.exportJson(path);
		source.sendFeedback(Component.literal("Exported " + waypointStore.all().size() + " waypoints to " + path));
		return 1;
	}

	private int runWaypointImportXaero(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		Path path = Paths.get(getString(context, "path"));
		String dimension = currentDimensionOrOverworld(source.getClient());
		List<Waypoint> parsed = WaypointImportExport.importXaero(path, dimension);
		int added = waypointStore.importList(parsed);
		source.sendFeedback(Component.literal("Imported " + added + " of " + parsed.size() + " Xaero waypoints from " + path + " (assumed dimension: " + dimension + ")"));
		return 1;
	}

	private int runWaypointImportLunar(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		Path path = Paths.get(getString(context, "path"));
		String dimension = currentDimensionOrOverworld(source.getClient());
		List<Waypoint> parsed = WaypointImportExport.importLunar(path, dimension);
		int added = waypointStore.importList(parsed);
		source.sendFeedback(Component.literal("Imported " + added + " of " + parsed.size() + " Lunar waypoints from " + path));
		return 1;
	}

	private int runWaypointImportJson(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		Path path = Paths.get(getString(context, "path"));
		int added = waypointStore.importJson(path);
		source.sendFeedback(Component.literal("Imported " + added + " waypoints from " + path));
		return 1;
	}

	private int runItemInfo(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			source.sendError(Component.literal("No player — join a world first."));
			return 0;
		}
		ItemStack stack = player.getMainHandItem();
		String label = "mainhand";
		if (stack.isEmpty()) {
			stack = player.getOffhandItem();
			label = "offhand";
		}
		if (stack.isEmpty()) {
			source.sendError(Component.literal("Hold the item in your main or off hand first."));
			return 0;
		}
		ItemDump.Result result = ItemDump.dump(stack, label);
		source.sendFeedback(Component.literal("%s x%d — \"%s\" (%d components)".formatted(
				result.itemId(), result.count(), result.hoverName(), result.chatLines().size())));
		result.chatLines().forEach(line -> source.sendFeedback(Component.literal("  " + line)));
		source.sendFeedback(Component.literal("Appended to " + result.file()));
		return 1;
	}

	private static String currentDimensionOrOverworld(Minecraft client) {
		return client.level != null ? client.level.dimension().identifier().toString() : "minecraft:overworld";
	}

	private static WaypointCategory parseCategory(String name) {
		try {
			return WaypointCategory.valueOf(name.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}

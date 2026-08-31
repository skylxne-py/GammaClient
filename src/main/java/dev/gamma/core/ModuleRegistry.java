package dev.gamma.core;

import dev.gamma.modules.basehunting.CrystalESP;
import dev.gamma.modules.basehunting.ElytraFinder;
import dev.gamma.modules.basehunting.HoleESP;
import dev.gamma.modules.basehunting.SusChunkFinder;
import dev.gamma.modules.donutsmp.FakeInventory;
import dev.gamma.modules.donutsmp.RenderDistanceExploit;
import dev.gamma.modules.donutsmp.ShardItemTimer;
import dev.gamma.modules.donutsmp.SpawnerFinder;
import dev.gamma.modules.esp.BlockESP;
import dev.gamma.modules.esp.Breadcrumbs;
import dev.gamma.modules.esp.EntityESP;
import dev.gamma.modules.esp.ItemESP;
import dev.gamma.modules.esp.LogoutSpots;
import dev.gamma.modules.esp.PlayerESP;
import dev.gamma.modules.esp.StorageESP;
import dev.gamma.modules.render.Freecam;
import dev.gamma.modules.render.Ambience;
import dev.gamma.modules.render.BlockHighlight;
import dev.gamma.modules.render.Chams;
import dev.gamma.modules.render.Freelook;
import dev.gamma.modules.render.Fullbright;
import dev.gamma.modules.render.NameTag;
import dev.gamma.modules.render.NoRender;
import dev.gamma.modules.render.Tracers;
import dev.gamma.modules.render.Trajectories;
import dev.gamma.modules.render.ViewModel;
import dev.gamma.modules.render.Xray;
import dev.gamma.modules.render.Zoom;
import dev.gamma.modules.misc.AutoReconnect;
import dev.gamma.modules.misc.AutoSign;
import dev.gamma.modules.misc.BetterTooltips;
import dev.gamma.modules.misc.DiscordRPC;
import dev.gamma.modules.misc.FakeCoordinates;
import dev.gamma.modules.misc.NameProtect;
import dev.gamma.modules.misc.ScoreboardEditor;
import dev.gamma.modules.misc.Spotify;
import dev.gamma.modules.world.NewChunks;
import dev.gamma.modules.world.StashFinder;
import dev.gamma.modules.world.WaypointsModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Holds every module. Registration is explicit and exhaustive in {@link #registerAll()} —
 * no classpath scanning, no reflection. Adding a module means creating its class and adding
 * one line here.
 */
public final class ModuleRegistry {

	private final List<Module> modules = new ArrayList<>();
	private final Map<String, Module> byName = new HashMap<>();

	public void registerAll() {
		// Phase 4 — ESP group
		register(new EntityESP());
		register(new PlayerESP());
		register(new ItemESP());
		register(new StorageESP());
		register(new BlockESP());
		register(new LogoutSpots());
		register(new Breadcrumbs());

		// Phase 4 — Render group
		register(new Xray());
		register(new Fullbright());
		register(new NoRender());
		register(new Ambience());
		register(new Zoom());
		register(new Freecam());
		register(new Freelook());
		register(new ViewModel());
		register(new BlockHighlight());
		register(new Trajectories());
		register(new Chams());
		register(new Tracers());
		register(new NameTag());

		// Phase 4 — Misc group
		register(new AutoReconnect());
		register(new AutoSign());
		register(new BetterTooltips());
		register(new NameProtect());
		register(new DiscordRPC());
		register(new FakeCoordinates());
		register(new ScoreboardEditor());
		register(new Spotify());

		// Phase 5 — chunk logging and new-chunk detection
		register(new NewChunks());

		// Phase 6 — waypoints, map overlay, stash scoring
		register(new WaypointsModule());
		register(new StashFinder());

		// Base Hunting group
		register(new SusChunkFinder());
		register(new CrystalESP());
		register(new HoleESP());
		register(new ElytraFinder());

		// DonutSMP group -- grouped by server rather than by function; see Category.DONUT_SMP.
		register(new ShardItemTimer());
		register(new SpawnerFinder());
		register(new FakeInventory());
		register(new RenderDistanceExploit());
	}

	private void register(Module module) {
		String key = module.name().toLowerCase(Locale.ROOT);
		if (byName.putIfAbsent(key, module) != null) {
			throw new IllegalStateException("Duplicate module name: " + module.name());
		}
		modules.add(module);
	}

	public Optional<Module> get(String name) {
		return Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
	}

	public List<Module> all() {
		return Collections.unmodifiableList(modules);
	}

	/**
	 * The modules a category tab should list — client-level ones excluded, since they live in the
	 * settings screen instead and showing them in both places would make one control look like two.
	 * They are still in {@link #all()}, so search, {@code .toggle} and config serialization reach
	 * them exactly as before.
	 */
	public List<Module> byCategory(Category category) {
		return modules.stream()
				.filter(module -> module.category() == category && !module.isClientLevel())
				.toList();
	}

	/** Modules that belong in the settings screen rather than a category — see {@link Module#isClientLevel()}. */
	public List<Module> clientLevel() {
		return modules.stream().filter(Module::isClientLevel).toList();
	}
}

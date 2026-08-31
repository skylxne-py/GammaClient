package dev.gamma.waypoints;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.gamma.Gamma;
import dev.gamma.config.ConfigManager;
import dev.gamma.core.GammaExecutor;
import dev.gamma.core.GammaPaths;
import dev.gamma.core.event.EventBus;
import dev.gamma.core.event.events.WorldLoadEvent;
import dev.gamma.core.event.events.WorldUnloadEvent;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Persistent, per-server waypoint storage under {@code gamma/waypoints/<server>.json} — same
 * profile-key/debounced-write shape as {@link dev.gamma.config.ConfigManager} and {@code
 * modules.esp.LogoutSpots}. An always-on core service (installed once from {@code GammaClient},
 * not a {@link dev.gamma.core.Module}), since waypoints must exist and persist regardless of
 * whether any particular rendering module is toggled on — same reasoning as {@code
 * ChunkObservationCollector} for chunk logging.
 *
 * <p>Publishes itself via {@link #instance}, the same static-reachability seam already
 * established for {@code Xray}/{@code NewChunks}/etc. (see the design notes): {@code
 * WaypointsModule}, {@code StashFinder}, {@code LogoutSpots}, the map overlay, and the command
 * layer all need to reach the live store with no constructor-injection path between them.
 */
public final class WaypointStore {

	private static final int CURRENT_VERSION = 1;
	private static final long SAVE_DEBOUNCE_MS = 500;

	public static volatile WaypointStore instance;

	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final List<Waypoint> waypoints = new CopyOnWriteArrayList<>();
	private volatile String serverKey;
	private volatile ScheduledFuture<?> pendingSave;

	public WaypointStore() {
		instance = this;
	}

	public void install(EventBus eventBus) {
		eventBus.subscribe(WorldLoadEvent.class, event -> onWorldLoad());
		eventBus.subscribe(WorldUnloadEvent.class, event -> onWorldUnload());
	}

	private void onWorldLoad() {
		String key = ConfigManager.currentProfileKey(Minecraft.getInstance());
		serverKey = key;
		waypoints.clear();
		GammaExecutor.execute(() -> {
			List<Waypoint> loaded = readWaypoints(pathFor(key));
			Minecraft.getInstance().execute(() -> {
				if (key.equals(serverKey)) {
					waypoints.addAll(loaded);
				}
			});
		});
	}

	private void onWorldUnload() {
		if (serverKey != null) {
			saveNow();
		}
		serverKey = null;
		waypoints.clear();
	}

	public List<Waypoint> all() {
		return List.copyOf(waypoints);
	}

	public List<Waypoint> forDimension(String dimension) {
		return waypoints.stream().filter(w -> w.dimension().equals(dimension)).toList();
	}

	public Optional<Waypoint> byName(String name) {
		return waypoints.stream().filter(w -> w.name().equalsIgnoreCase(name)).findFirst();
	}

	public void add(Waypoint waypoint) {
		waypoints.add(waypoint);
		requestSave();
	}

	/** @return whether a waypoint with that name existed and was removed. */
	public boolean remove(String name) {
		boolean removed = waypoints.removeIf(w -> w.name().equalsIgnoreCase(name));
		if (removed) {
			requestSave();
		}
		return removed;
	}

	public static UUID newId() {
		return UUID.randomUUID();
	}

	// -- persistence --------------------------------------------------------

	public void requestSave() {
		if (serverKey == null) {
			return;
		}
		List<Waypoint> snapshot = List.copyOf(waypoints);
		Path file = pathFor(serverKey);
		ScheduledFuture<?> previous = pendingSave;
		if (previous != null) {
			previous.cancel(false);
		}
		pendingSave = GammaExecutor.schedule(() -> writeWaypoints(file, snapshot), SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
	}

	public void saveNow() {
		if (serverKey == null) {
			return;
		}
		writeWaypoints(pathFor(serverKey), List.copyOf(waypoints));
	}

	private Path pathFor(String server) {
		return GammaPaths.dir("waypoints").resolve(GammaPaths.sanitizeFileName(server) + ".json");
	}

	// -- own JSON format (also used for export/import) -----------------------

	public void exportJson(Path file) {
		writeWaypoints(file, List.copyOf(waypoints));
	}

	/** @return how many waypoints were added (name collisions with existing waypoints are skipped). */
	public int importJson(Path file) {
		List<Waypoint> loaded = readWaypoints(file);
		return mergeIn(loaded);
	}

	/** Used by {@link WaypointImportExport} once it's parsed a foreign format into plain {@link Waypoint}s. */
	public int importList(List<Waypoint> incoming) {
		return mergeIn(incoming);
	}

	private int mergeIn(List<Waypoint> incoming) {
		int added = 0;
		for (Waypoint w : incoming) {
			if (byName(w.name()).isPresent()) {
				continue;
			}
			waypoints.add(w);
			added++;
		}
		if (added > 0) {
			requestSave();
		}
		return added;
	}

	private List<Waypoint> readWaypoints(Path file) {
		if (!Files.isRegularFile(file)) {
			return List.of();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject root = gson.fromJson(reader, JsonObject.class);
			if (root == null || !root.has("waypoints")) {
				return List.of();
			}
			List<Waypoint> result = new ArrayList<>();
			for (var element : root.getAsJsonArray("waypoints")) {
				try {
					result.add(fromJson(element.getAsJsonObject()));
				} catch (RuntimeException e) {
					Gamma.LOGGER.warn("Skipping unreadable waypoint entry in {}", file, e);
				}
			}
			return result;
		} catch (IOException | JsonParseException e) {
			Gamma.LOGGER.error("Failed to read waypoints file {}, ignoring", file, e);
			return List.of();
		}
	}

	private void writeWaypoints(Path file, List<Waypoint> snapshot) {
		JsonObject root = new JsonObject();
		root.addProperty("version", CURRENT_VERSION);
		JsonArray array = new JsonArray();
		for (Waypoint w : snapshot) {
			array.add(toJson(w));
		}
		root.add("waypoints", array);
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			gson.toJson(root, writer);
		} catch (IOException e) {
			Gamma.LOGGER.error("Failed to write waypoints file {}", file, e);
		}
	}

	private static JsonObject toJson(Waypoint w) {
		JsonObject o = new JsonObject();
		o.addProperty("id", w.id().toString());
		o.addProperty("name", w.name());
		o.addProperty("category", w.category().name());
		o.addProperty("dimension", w.dimension());
		o.addProperty("x", w.x());
		o.addProperty("y", w.y());
		o.addProperty("z", w.z());
		if (w.colorOverride() != null) {
			o.addProperty("color", w.colorOverride());
		}
		if (w.iconOverride() != null) {
			o.addProperty("icon", w.iconOverride().name());
		}
		o.addProperty("beaconBeam", w.beaconBeam());
		o.addProperty("visible", w.visible());
		o.addProperty("createdAt", w.createdAtMillis());
		o.addProperty("source", w.source().name());
		return o;
	}

	private static Waypoint fromJson(JsonObject o) {
		UUID id = o.has("id") ? UUID.fromString(o.get("id").getAsString()) : newId();
		String name = o.get("name").getAsString();
		WaypointCategory category = parseEnum(WaypointCategory.class, o.get("category").getAsString(), WaypointCategory.MISC);
		String dimension = o.get("dimension").getAsString();
		double x = o.get("x").getAsDouble();
		double y = o.get("y").getAsDouble();
		double z = o.get("z").getAsDouble();
		Integer color = o.has("color") ? o.get("color").getAsInt() : null;
		WaypointIcon icon = o.has("icon") ? parseEnum(WaypointIcon.class, o.get("icon").getAsString(), null) : null;
		boolean beaconBeam = !o.has("beaconBeam") || o.get("beaconBeam").getAsBoolean();
		boolean visible = !o.has("visible") || o.get("visible").getAsBoolean();
		long createdAt = o.has("createdAt") ? o.get("createdAt").getAsLong() : System.currentTimeMillis();
		WaypointSource source = o.has("source") ? parseEnum(WaypointSource.class, o.get("source").getAsString(), WaypointSource.IMPORT) : WaypointSource.IMPORT;
		return new Waypoint(id, name, category, dimension, x, y, z, color, icon, beaconBeam, visible, createdAt, source);
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
		try {
			return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}
}

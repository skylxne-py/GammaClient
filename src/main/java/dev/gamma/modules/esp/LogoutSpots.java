package dev.gamma.modules.esp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.gamma.Gamma;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.ColorSetting;
import dev.gamma.config.setting.IntSetting;
import dev.gamma.core.Category;
import dev.gamma.core.GammaExecutor;
import dev.gamma.core.GammaPaths;
import dev.gamma.core.Module;
import dev.gamma.core.event.Subscription;
import dev.gamma.core.event.events.TickEvent;
import dev.gamma.core.event.events.WorldLoadEvent;
import dev.gamma.core.event.events.WorldRenderExtractEvent;
import dev.gamma.render.RenderPass;
import dev.gamma.render.Renderer3D;
import dev.gamma.util.ColorUtil;
import dev.gamma.waypoints.Waypoint;
import dev.gamma.waypoints.WaypointCategory;
import dev.gamma.waypoints.WaypointSource;
import dev.gamma.waypoints.WaypointStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records where a nearby player entity disappears — the strongest, simplest signal a client can
 * observe for "this player disconnected" without a dedicated disconnect packet to key off of.
 * A ghost box + timestamped label marks the spot, persisted as JSON per server (this predates
 * the Phase 5 chunk DB, so it gets its own tiny file rather than a shared store).
 */
public final class LogoutSpots extends Module {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final SimpleDateFormat TIMESTAMP_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	private final ColorSetting color = register(new ColorSetting("Color", "Ghost box color.", 0xFFFFFFFF));
	private final IntSetting maxAgeMinutes = register(new IntSetting("MaxAgeMinutes", "Stop rendering a spot this many minutes after it was recorded (0 = never expire).", 0, 0, 1440));
	private final BoolSetting persist = register(new BoolSetting("Persist", "Save recorded spots to disk, per server.", true));
	private final BoolSetting createWaypoint = register(new BoolSetting("CreateWaypoint", "Also drop a Logout-category waypoint for each recorded spot.", false));

	private final List<LogoutSpot> spots = new CopyOnWriteArrayList<>();
	private Map<UUID, TrackedPlayer> lastSeen = new HashMap<>();
	private String serverKey;

	private Subscription tickSubscription;
	private Subscription extractSubscription;
	private Subscription worldLoadSubscription;

	public LogoutSpots() {
		super("LogoutSpots", "Marks where nearby players disappear from view — usually a disconnect.", Category.ESP);
	}

	@Override
	protected void onEnable() {
		lastSeen = new HashMap<>();
		serverKey = currentServerKey();
		loadSpots();
		tickSubscription = listen(TickEvent.class, this::onTick);
		extractSubscription = listen(WorldRenderExtractEvent.class, this::onExtract);
		worldLoadSubscription = listen(WorldLoadEvent.class, event -> {
			serverKey = currentServerKey();
			spots.clear();
			lastSeen = new HashMap<>();
			loadSpots();
		});
	}

	@Override
	protected void onDisable() {
		Gamma.EVENT_BUS.unsubscribe(tickSubscription);
		Gamma.EVENT_BUS.unsubscribe(extractSubscription);
		Gamma.EVENT_BUS.unsubscribe(worldLoadSubscription);
		spots.clear();
	}

	private void onTick(TickEvent event) {
		if (event.phase() != TickEvent.Phase.END) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) {
			return;
		}

		String dimension = client.level.dimension().identifier().toString();
		Map<UUID, TrackedPlayer> current = new HashMap<>();
		Set<UUID> seenThisTick = new HashSet<>();
		for (AbstractClientPlayer player : client.level.players()) {
			if (player == client.player) {
				continue;
			}
			seenThisTick.add(player.getUUID());
			current.put(player.getUUID(), new TrackedPlayer(player.getGameProfile().name(), player.position(), dimension));
		}

		for (Map.Entry<UUID, TrackedPlayer> entry : lastSeen.entrySet()) {
			if (!seenThisTick.contains(entry.getKey())) {
				recordSpot(entry.getValue());
			}
		}
		lastSeen = current;
	}

	private void recordSpot(TrackedPlayer player) {
		LogoutSpot spot = new LogoutSpot(player.name(), player.position(), System.currentTimeMillis());
		spots.add(spot);
		Gamma.LOGGER.info("LogoutSpots: recorded {} at {}", spot.playerName(), spot.position());
		if (persist.get()) {
			saveSpots();
		}
		if (createWaypoint.get()) {
			WaypointStore store = WaypointStore.instance;
			if (store != null) {
				Vec3 pos = player.position();
				store.add(new Waypoint(WaypointStore.newId(), player.name() + " logout", WaypointCategory.LOGOUT,
						player.dimension(), pos.x, pos.y, pos.z, null, null, false, true, spot.timestampMillis(), WaypointSource.LOGOUT_AUTO));
			}
		}
	}

	private void onExtract(WorldRenderExtractEvent event) {
		long maxAgeMillis = maxAgeMinutes.get() * 60_000L;
		long now = System.currentTimeMillis();
		int drawColor = color.get();
		for (LogoutSpot spot : spots) {
			if (maxAgeMillis > 0 && now - spot.timestampMillis() > maxAgeMillis) {
				continue;
			}
			AABB box = new AABB(spot.position(), spot.position()).inflate(0.35, 0.9, 0.35);
			Renderer3D.outlinedBox(box, drawColor, ColorUtil.withAlpha(drawColor, 50), RenderPass.THROUGH_WALLS);
			String label = spot.playerName() + " - " + TIMESTAMP_FORMAT.format(new Date(spot.timestampMillis()));
			Renderer3D.text3d(spot.position().add(0, 1.1, 0), label, drawColor, RenderPass.THROUGH_WALLS);
		}
	}

	private static String currentServerKey() {
		Minecraft client = Minecraft.getInstance();
		if (client.hasSingleplayerServer()) {
			return "singleplayer";
		}
		ServerData server = client.getCurrentServer();
		return server != null ? server.ip : "unknown";
	}

	private Path spotsPath() {
		return GammaPaths.dir("logouts").resolve(GammaPaths.sanitizeFileName(serverKey) + ".json");
	}

	private void loadSpots() {
		String key = serverKey;
		GammaExecutor.execute(() -> {
			Path file = GammaPaths.dir("logouts").resolve(GammaPaths.sanitizeFileName(key) + ".json");
			List<LogoutSpot> loaded = readSpots(file);
			Minecraft.getInstance().execute(() -> {
				if (key.equals(serverKey)) {
					spots.addAll(loaded);
				}
			});
		});
	}

	private void saveSpots() {
		List<LogoutSpot> snapshot = new ArrayList<>(spots);
		Path file = spotsPath();
		GammaExecutor.execute(() -> writeSpots(file, snapshot));
	}

	private static List<LogoutSpot> readSpots(Path file) {
		if (!Files.isRegularFile(file)) {
			return List.of();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonArray array = GSON.fromJson(reader, JsonArray.class);
			List<LogoutSpot> result = new ArrayList<>();
			for (var element : array) {
				JsonObject o = element.getAsJsonObject();
				result.add(new LogoutSpot(
						o.get("player").getAsString(),
						new Vec3(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble()),
						o.get("timestamp").getAsLong()));
			}
			return result;
		} catch (IOException | JsonParseException e) {
			Gamma.LOGGER.error("Failed to read logout spots {}, ignoring", file, e);
			return List.of();
		}
	}

	private static void writeSpots(Path file, List<LogoutSpot> spots) {
		JsonArray array = new JsonArray();
		for (LogoutSpot spot : spots) {
			JsonObject o = new JsonObject();
			o.addProperty("player", spot.playerName());
			o.addProperty("x", spot.position().x);
			o.addProperty("y", spot.position().y);
			o.addProperty("z", spot.position().z);
			o.addProperty("timestamp", spot.timestampMillis());
			array.add(o);
		}
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(array, writer);
		} catch (IOException e) {
			Gamma.LOGGER.error("Failed to write logout spots {}", file, e);
		}
	}

	private record TrackedPlayer(String name, Vec3 position, String dimension) {
	}

	private record LogoutSpot(String playerName, Vec3 position, long timestampMillis) {
	}
}

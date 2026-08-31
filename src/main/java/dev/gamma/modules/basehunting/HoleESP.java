package dev.gamma.modules.basehunting;

import dev.gamma.Gamma;
import dev.gamma.config.setting.ColorSetting;
import dev.gamma.config.setting.DoubleSetting;
import dev.gamma.config.setting.IntSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.core.event.Subscription;
import dev.gamma.core.event.events.ChunkReceiveEvent;
import dev.gamma.core.event.events.PacketReceiveEvent;
import dev.gamma.core.event.events.TickEvent;
import dev.gamma.core.event.events.WorldRenderExtractEvent;
import dev.gamma.render.Culling;
import dev.gamma.render.RenderPass;
import dev.gamma.render.Renderer3D;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Marks dug shafts — vertical holes in the terrain — with a translucent beam, so they can be
 * spotted from the air at range. A hole in the ground is one of the strongest surface tells that
 * somebody dug down to a base underneath it.
 *
 * <h2>What counts as a hole</h2>
 *
 * <p>A column is a candidate when the {@code WORLD_SURFACE} heightmap puts its open-air floor at
 * least {@code MinDepth} <em>below sea level</em>, and something taller stands next to it. Depth is
 * measured from sea level rather than from the surrounding ground, so the number means the same
 * thing in every biome and a shaft dug into a mountainside only counts once it gets down past sea
 * level — which is where the things worth finding are. The neighbour test survives only as "this is
 * a hole, not flat low ground"; it no longer sets the depth.
 *
 * <p>Working off the heightmap rather than walking block states means the whole scan is a handful of
 * packed-array reads per column instead of the section sweep {@code BlockESP} needs, which is what
 * makes a full render-distance scan affordable.
 *
 * <p>Two consequences fall out of using {@code WORLD_SURFACE} and are worth knowing: the hole must
 * be <em>open to the sky</em> (the heightmap is the highest non-air block, so anything with a roof
 * over it is invisible to this — caves and covered bases will not show), and water and lava count
 * as surface, so an ocean floor is not a hole.
 *
 * <h2>One beam per hole</h2>
 *
 * <p>Candidate columns are flood-filled 4-connected across chunk borders into a single component,
 * so three shafts dug side by side with no wall between them are one hole with one beam, not
 * three. Each component is keyed by its lowest-ordered column, which is what makes the merge
 * survive being rediscovered from a neighbouring chunk's scan: the same hole always produces the
 * same key, so the second discovery overwrites the first instead of adding a duplicate.
 *
 * <p>The beam itself is always exactly one block across, whatever the hole's real footprint — drawn
 * in the member column nearest the middle of it. The footprint is still tracked, because stepping
 * into any part of a merged hole has to count as being inside it even though only one column is
 * drawn.
 *
 * <p>{@code MaxWidth} is what separates a dug shaft from a landscape. Plenty of ravines, quarry
 * edges and cliff bottoms sit well below sea level, and without a width cap the beam list would be
 * mostly terrain. A component that floods wider than {@code MaxWidth} in either axis is abandoned
 * as soon as it does, which also keeps the fill cheap on open ground.
 */
public final class HoleESP extends Module {

	private static final int CHUNKS_PER_TICK = 8;

	/**
	 * How uneven a hole's floor may be and still count as one hole. Shafts are rarely dug to a
	 * perfectly flat bottom, and without slack a two-block step would split one hole into two beams.
	 */
	private static final int FLOOR_TOLERANCE = 3;

	/** Half-diagonal of a chunk's footprint, so the cheap chunk-level distance reject can't clip a corner. */
	private static final double CHUNK_BOUNDING_RADIUS = 11.32;

	private final IntSetting minDepth = register(new IntSetting("MinDepth",
			"How far below sea level the hole bottom must sit before it gets a beam. Measured from sea level, not from the ground around it, so a shaft in a mountainside only counts once it gets down past sea level.", 30, 3, 128));
	private final IntSetting maxWidth = register(new IntSetting("MaxWidth",
			"Holes wider than this across are treated as terrain, not a dug shaft. Raise it to catch big openings, at the cost of beams on ravines and cliffs.", 8, 1, 32));
	private final ColorSetting color = register(new ColorSetting("Color",
			"Beam colour. The alpha channel is the opacity — a beam is a tall shape and reads better faint than solid.", 0x4033DDFF));
	private final DoubleSetting maxDistance = register(new DoubleSetting("MaxDistance",
			"Stop drawing beams beyond this many blocks.", 256.0, 16.0, 1024.0));

	/** Keyed by the hole's lowest-ordered column, so the same hole found twice is stored once. */
	private final Map<Long, Hole> holes = new ConcurrentHashMap<>();
	/** A set, not a deque: block updates re-queue nine chunks at a time and this is checked for duplicates on every one, which an ArrayDeque answers in linear time. Insertion order is still the scan order. */
	private final Set<ChunkPos> pendingChunks = new LinkedHashSet<>();

	private Subscription extractSubscription;
	private Subscription chunkSubscription;
	private Subscription packetSubscription;
	private Subscription tickSubscription;

	/**
	 * An immutable draw payload, per the project's extraction/render split — the scan produces these
	 * on tick and the extract handler only reads them.
	 *
	 * <p>{@code floorY} is the deepest open-air block in the hole (where the beam starts) and
	 * {@code rimY} is the top of the wall around it (what "inside the hole" is measured against).
	 * Bounds are inclusive block coordinates.
	 *
	 * <p>{@code beamX}/{@code beamZ} are the single column the beam is drawn in. The bounds still
	 * cover the whole hole because that is what the "player is inside" test needs, but the beam
	 * itself is always one block, never the footprint.
	 */
	public record Hole(int minX, int minZ, int maxX, int maxZ, int beamX, int beamZ, int floorY, int rimY) {

		boolean containsHorizontally(double x, double z) {
			return x >= minX && x < maxX + 1 && z >= minZ && z < maxZ + 1;
		}
	}

	public HoleESP() {
		super("HoleESP", "Beams over dug shafts in the terrain — the surface tell for a base underneath.", Category.BASE_HUNTING);
	}

	@Override
	protected void onEnable() {
		holes.clear();
		pendingChunks.clear();
		Minecraft client = Minecraft.getInstance();
		if (client.level != null && client.player != null) {
			ChunkPos center = ChunkPos.containing(client.player.blockPosition());
			int radius = client.options.renderDistance().get();
			for (int cx = center.x() - radius; cx <= center.x() + radius; cx++) {
				for (int cz = center.z() - radius; cz <= center.z() + radius; cz++) {
					if (client.level.hasChunk(cx, cz)) {
						pendingChunks.add(new ChunkPos(cx, cz));
					}
				}
			}
		}
		extractSubscription = listen(WorldRenderExtractEvent.class, this::onExtract);
		chunkSubscription = listen(ChunkReceiveEvent.class, this::onChunkReceive);
		packetSubscription = listen(PacketReceiveEvent.class, this::onPacketReceive);
		tickSubscription = listen(TickEvent.class, this::onTick);
	}

	@Override
	protected void onDisable() {
		Gamma.EVENT_BUS.unsubscribe(extractSubscription);
		Gamma.EVENT_BUS.unsubscribe(chunkSubscription);
		Gamma.EVENT_BUS.unsubscribe(packetSubscription);
		Gamma.EVENT_BUS.unsubscribe(tickSubscription);
		holes.clear();
		pendingChunks.clear();
	}

	private void onTick(TickEvent event) {
		if (event.phase() != TickEvent.Phase.END) {
			return;
		}
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}
		Iterator<ChunkPos> queued = pendingChunks.iterator();
		for (int i = 0; i < CHUNKS_PER_TICK && queued.hasNext(); i++) {
			ChunkPos pos = queued.next();
			queued.remove();
			if (level.hasChunk(pos.x(), pos.z())) {
				scanChunk(level, pos);
			}
		}
	}

	private void onChunkReceive(ChunkReceiveEvent event) {
		queueWithNeighbours(event.chunk().getPos());
	}

	private void onPacketReceive(PacketReceiveEvent event) {
		switch (event.packet()) {
			case ClientboundBlockUpdatePacket packet -> queueWithNeighbours(ChunkPos.containing(packet.getPos()));
			case ClientboundSectionBlocksUpdatePacket packet -> packet.runUpdates((pos, state) -> queueWithNeighbours(ChunkPos.containing(pos)));
			default -> {
			}
		}
	}

	/**
	 * A hole is stored against whichever chunk owns its lowest column, and a rescan only clears the
	 * holes it owns — so editing terrain in one chunk has to re-run its neighbours too, or a hole
	 * that straddles the border and is keyed next door would never be re-evaluated.
	 */
	private void queueWithNeighbours(ChunkPos pos) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				pendingChunks.add(new ChunkPos(pos.x() + dx, pos.z() + dz));
			}
		}
	}

	// -- scan ---------------------------------------------------------------

	private void scanChunk(ClientLevel level, ChunkPos chunk) {
		// Drop what this chunk previously owned before re-deriving it, so a filled-in hole goes away.
		holes.keySet().removeIf(key -> chunkOf(key).equals(chunk));

		int minDepthValue = minDepth.get();
		int maxWidthValue = maxWidth.get();
		Set<Long> visited = new HashSet<>();

		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				int worldX = chunk.getMinBlockX() + x;
				int worldZ = chunk.getMinBlockZ() + z;
				long key = packColumn(worldX, worldZ);
				if (visited.contains(key) || !isSeed(level, worldX, worldZ, minDepthValue)) {
					continue;
				}
				Hole hole = floodFill(level, worldX, worldZ, minDepthValue, maxWidthValue, visited);
				if (hole != null) {
					holes.put(packColumn(hole.minX(), hole.minZ()), hole);
				}
			}
		}
	}

	/**
	 * A column that drops at least {@code minDepth} below sea level and has a wall next to it.
	 *
	 * <p>Depth is measured from sea level rather than from the surrounding ground, so
	 * {@code MinDepth} means "this far under sea level" everywhere. The practical effect is that a
	 * shaft dug into a mountainside does not count until it gets down past sea level, which is
	 * where anything worth finding actually is.
	 *
	 * <p>The wall test stays, but only as "is there anything taller adjacent" — it is what
	 * separates a hole from flat low ground, and the depth number no longer depends on it.
	 */
	private boolean isSeed(ClientLevel level, int x, int z, int minDepth) {
		int floor = surface(level, x, z);
		if (floor == UNKNOWN || level.getSeaLevel() - floor < minDepth) {
			return false;
		}
		return highestNeighbour(level, x, z) > floor;
	}

	private int highestNeighbour(ClientLevel level, int x, int z) {
		int highest = Integer.MIN_VALUE;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) {
					continue;
				}
				int neighbour = surface(level, x + dx, z + dz);
				if (neighbour != UNKNOWN) {
					highest = Math.max(highest, neighbour);
				}
			}
		}
		return highest;
	}

	/**
	 * Grows the hole outward from one seed column across chunk borders, and gives up the moment the
	 * bounding box outgrows {@code maxWidth} — open ground would otherwise flood indefinitely.
	 *
	 * @return the merged hole, or null if it was rejected as terrain or turned out too shallow
	 */
	private Hole floodFill(ClientLevel level, int seedX, int seedZ, int minDepth, int maxWidth, Set<Long> visited) {
		int seedFloor = surface(level, seedX, seedZ);
		Deque<long[]> queue = new ArrayDeque<>();
		List<long[]> members = new ArrayList<>();
		Set<Long> claimed = new HashSet<>();

		queue.add(new long[] {seedX, seedZ});
		claimed.add(packColumn(seedX, seedZ));

		int minX = seedX;
		int maxX = seedX;
		int minZ = seedZ;
		int maxZ = seedZ;
		int floorY = seedFloor;

		while (!queue.isEmpty()) {
			long[] column = queue.poll();
			int x = (int) column[0];
			int z = (int) column[1];
			members.add(column);
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
			minZ = Math.min(minZ, z);
			maxZ = Math.max(maxZ, z);
			floorY = Math.min(floorY, surface(level, x, z));
			if (maxX - minX + 1 > maxWidth || maxZ - minZ + 1 > maxWidth) {
				// Terrain, not a shaft. Everything reached is marked visited so the outer loop
				// doesn't immediately re-seed from the next column of the same landscape.
				visited.addAll(claimed);
				return null;
			}
			for (int[] step : NEIGHBOURS) {
				int nx = x + step[0];
				int nz = z + step[1];
				long key = packColumn(nx, nz);
				if (claimed.contains(key)) {
					continue;
				}
				int neighbourFloor = surface(level, nx, nz);
				if (neighbourFloor == UNKNOWN || Math.abs(neighbourFloor - seedFloor) > FLOOR_TOLERANCE) {
					continue;
				}
				claimed.add(key);
				queue.add(new long[] {nx, nz});
			}
		}

		visited.addAll(claimed);

		if (level.getSeaLevel() - floorY < minDepth) {
			return null;
		}

		// The rim is the tallest wall anywhere around the finished component. It no longer feeds the
		// depth test -- that is measured from sea level now -- but it is still the lip you would
		// climb out over, which is what "the player is inside this hole" is measured against.
		int rimY = Integer.MIN_VALUE;
		for (long[] column : members) {
			rimY = Math.max(rimY, highestNeighbourOutside(level, (int) column[0], (int) column[1], claimed));
		}
		if (rimY == Integer.MIN_VALUE) {
			return null;
		}

		// The beam is a single column, so one has to be chosen: the member nearest the centre of the
		// footprint, which keeps it visually over the hole even when the shape is not a rectangle.
		double centerX = (minX + maxX) / 2.0;
		double centerZ = (minZ + maxZ) / 2.0;
		long[] beam = members.getFirst();
		double best = Double.MAX_VALUE;
		for (long[] column : members) {
			double dx = column[0] - centerX;
			double dz = column[1] - centerZ;
			double distance = dx * dx + dz * dz;
			if (distance < best) {
				best = distance;
				beam = column;
			}
		}
		return new Hole(minX, minZ, maxX, maxZ, (int) beam[0], (int) beam[1], floorY, rimY);
	}

	/** Tallest neighbouring column that isn't part of the hole itself — the lip you'd climb out over. */
	private int highestNeighbourOutside(ClientLevel level, int x, int z, Set<Long> component) {
		int highest = Integer.MIN_VALUE;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0 || component.contains(packColumn(x + dx, z + dz))) {
					continue;
				}
				int neighbour = surface(level, x + dx, z + dz);
				if (neighbour != UNKNOWN) {
					highest = Math.max(highest, neighbour);
				}
			}
		}
		return highest;
	}

	private static final int UNKNOWN = Integer.MIN_VALUE;

	private static final int[][] NEIGHBOURS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	/**
	 * Y of the lowest open-air block in this column, or {@link #UNKNOWN} if the chunk isn't loaded.
	 *
	 * <p>The loaded check is not optional: {@code Level.getHeight} answers sea level + 1 for an
	 * absent chunk rather than failing, which at the edge of render distance would invent a wall
	 * (or a pit) out of nothing and beam every chunk border.
	 */
	private static int surface(ClientLevel level, int x, int z) {
		if (!level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))) {
			return UNKNOWN;
		}
		return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
	}

	private static long packColumn(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	private static ChunkPos chunkOf(long columnKey) {
		return new ChunkPos((int) (columnKey >> 32) >> 4, (int) columnKey >> 4);
	}

	// -- render -------------------------------------------------------------

	private void onExtract(WorldRenderExtractEvent event) {
		Camera camera = event.context().camera();
		ClientLevel level = event.context().level();
		Vec3 eye = camera.position();
		double max = maxDistance.get();
		int beamColor = color.get();
		int top = level.getMaxY();

		// Whichever hole the player is standing in, if any -- its beam is skipped so dropping in
		// doesn't leave a column of colour filling the screen.
		Vec3 feet = playerPosition();

		for (Iterator<Map.Entry<Long, Hole>> it = holes.entrySet().iterator(); it.hasNext(); ) {
			Hole hole = it.next().getValue();
			double beamCenterX = hole.beamX() + 0.5;
			double beamCenterZ = hole.beamZ() + 0.5;
			double dx = beamCenterX - eye.x;
			double dz = beamCenterZ - eye.z;
			if (dx * dx + dz * dz > (max + CHUNK_BOUNDING_RADIUS) * (max + CHUNK_BOUNDING_RADIUS)) {
				continue;
			}
			if (!Culling.withinDistance(eye, beamCenterX, hole.floorY(), beamCenterZ, max)) {
				continue;
			}
			// The footprint, not the beam column: stepping into any part of a merged hole counts as
			// being in it, even though only one of its columns is drawn.
			if (feet != null && hole.containsHorizontally(feet.x, feet.z) && feet.y <= hole.rimY()) {
				continue;
			}
			// Always exactly one block across, however wide the hole underneath it is.
			AABB beam = new AABB(hole.beamX(), hole.floorY(), hole.beamZ(), hole.beamX() + 1, top, hole.beamZ() + 1);
			if (!Culling.isVisible(camera.getCullFrustum(), beam)) {
				continue;
			}
			Renderer3D.filledBox(beam, beamColor, RenderPass.THROUGH_WALLS);
		}
	}

	private static Vec3 playerPosition() {
		Minecraft client = Minecraft.getInstance();
		return client.player == null ? null : client.player.position();
	}
}

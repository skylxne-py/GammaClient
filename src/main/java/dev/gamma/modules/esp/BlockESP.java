package dev.gamma.modules.esp;

import dev.gamma.Gamma;
import dev.gamma.config.setting.BlockListSetting;
import dev.gamma.config.setting.ColorSetting;
import dev.gamma.config.setting.DoubleSetting;
import dev.gamma.config.setting.EnumSetting;
import dev.gamma.config.setting.IntSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.core.event.Subscription;
import dev.gamma.core.event.events.ChunkReceiveEvent;
import dev.gamma.core.event.events.PacketReceiveEvent;
import dev.gamma.core.event.events.TickEvent;
import dev.gamma.core.event.events.WorldRenderExtractEvent;
import dev.gamma.render.Culling;
import dev.gamma.render.EspMode;
import dev.gamma.render.EspShapeRenderer;
import dev.gamma.render.RenderPass;
import dev.gamma.render.ShapeBuilder;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Arbitrary block list ESP with a real incremental scan (project convention: "rescan on block update,
 * not a full sweep every tick"). A newly-enabled scan of every loaded chunk is spread over
 * ticks ({@link #pendingChunks}, a few per {@link TickEvent}) rather than done in one burst —
 * a full render-distance sweep is tens of millions of block reads, too much for one frame.
 * After that, {@link ChunkReceiveEvent} covers newly streamed-in chunks and
 * {@link PacketReceiveEvent} (matched against the two vanilla block-update packet types) keeps
 * already-scanned chunks current without ever re-scanning them wholesale.
 */
public final class BlockESP extends Module {

	private static final int CHUNKS_PER_TICK = 4;

	/**
	 * Half-diagonal of a chunk's 16×16 footprint (√2 × 8), added to MaxDistance so the cheap
	 * chunk-level reject can never discard a chunk whose corner is still within range.
	 */
	private static final double CHUNK_BOUNDING_RADIUS = 11.32;

	private final BlockListSetting blocks = register(new BlockListSetting("Blocks", "Blocks to highlight.", List.of(Blocks.DIAMOND_ORE, Blocks.ANCIENT_DEBRIS)));
	private final EnumSetting<EspMode> mode = register(new EnumSetting<>("Mode", "Box / tracer / both.", EspMode.class, EspMode.BOX));
	private final ColorSetting color = register(new ColorSetting("Color", "Highlight color.", 0xFF33DDFF));
	private final DoubleSetting maxDistance = register(new DoubleSetting("MaxDistance", "Max render distance, in blocks.", 48.0, 8.0, 1024.0));
	private final IntSetting maxHeight = register(new IntSetting("MaxHeight", "Only show matches at or below this Y, to cut surface noise when hunting underground. 320 = no limit.", Culling.HEIGHT_LIMIT_OFF, -64, Culling.HEIGHT_LIMIT_OFF));

	private final Map<Long, Set<BlockPos>> matchesByChunk = new ConcurrentHashMap<>();
	private final Deque<ChunkPos> pendingChunks = new ArrayDeque<>();

	private Subscription extractSubscription;
	private Subscription chunkSubscription;
	private Subscription packetSubscription;
	private Subscription tickSubscription;

	public BlockESP() {
		super("BlockESP", "Highlights an arbitrary block list through walls.", Category.ESP);
	}

	@Override
	protected void onEnable() {
		matchesByChunk.clear();
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
		matchesByChunk.clear();
		pendingChunks.clear();
	}

	private void onTick(TickEvent event) {
		if (event.phase() != TickEvent.Phase.END) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return;
		}
		for (int i = 0; i < CHUNKS_PER_TICK && !pendingChunks.isEmpty(); i++) {
			ChunkPos pos = pendingChunks.poll();
			if (client.level.hasChunk(pos.x(), pos.z())) {
				scanChunk(client.level, client.level.getChunk(pos.x(), pos.z()));
			}
		}
	}

	private void onChunkReceive(ChunkReceiveEvent event) {
		scanChunk(event.level(), event.chunk());
	}

	private void onPacketReceive(PacketReceiveEvent event) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}
		switch (event.packet()) {
			case ClientboundBlockUpdatePacket packet -> updatePosition(packet.getPos(), packet.getBlockState());
			case ClientboundSectionBlocksUpdatePacket packet -> packet.runUpdates(this::updatePosition);
			default -> {
			}
		}
	}

	/**
	 * Section-at-a-time, gated on the section's own palette. A full-column walk is
	 * 16×16×(worldHeight) {@code getBlockState} calls — around 98k per chunk in a 384-tall
	 * overworld, and at {@link #CHUNKS_PER_TICK} that was ~400k block reads on the client thread
	 * every tick, which is what the profiler chart was showing as spikes.
	 *
	 * <p>{@link LevelChunkSection#maybeHas} answers "could this section contain a match?" straight
	 * off the palette, without touching the block storage at all. Ores and containers are sparse,
	 * so the overwhelming majority of sections are rejected by that one call, and the expensive
	 * inner loop only ever runs on sections that genuinely hold something. The predicate is built
	 * once per chunk rather than per section — {@code maybeHas} is called up to 24 times per
	 * chunk and the capture would otherwise allocate on every one.
	 */
	private void scanChunk(ClientLevel level, LevelChunk chunk) {
		Set<BlockPos> matches = new HashSet<>();
		ChunkPos pos = chunk.getPos();
		Predicate<BlockState> matcher = state -> blocks.contains(state.getBlock());
		LevelChunkSection[] sections = chunk.getSections();
		for (int index = 0; index < sections.length; index++) {
			LevelChunkSection section = sections[index];
			if (section.hasOnlyAir() || !section.maybeHas(matcher)) {
				continue;
			}
			// Section 0 starts at the world's minimum Y, and sections are 16 tall and
			// section-aligned, so the index converts to a block Y by a plain shift.
			int baseY = level.getMinY() + (index << 4);
			for (int y = 0; y < 16; y++) {
				for (int x = 0; x < 16; x++) {
					for (int z = 0; z < 16; z++) {
						if (matcher.test(section.getBlockState(x, y, z))) {
							matches.add(new BlockPos(pos.getMinBlockX() + x, baseY + y, pos.getMinBlockZ() + z));
						}
					}
				}
			}
		}
		if (matches.isEmpty()) {
			matchesByChunk.remove(pos.pack());
		} else {
			Set<BlockPos> concurrentMatches = ConcurrentHashMap.newKeySet(matches.size());
			concurrentMatches.addAll(matches);
			matchesByChunk.put(pos.pack(), concurrentMatches);
		}
	}

	private void updatePosition(BlockPos pos, BlockState state) {
		long key = ChunkPos.containing(pos).pack();
		boolean matches = blocks.contains(state.getBlock());
		Set<BlockPos> set = matchesByChunk.get(key);
		if (matches) {
			if (set == null) {
				set = ConcurrentHashMap.newKeySet();
				matchesByChunk.put(key, set);
			}
			set.add(pos.immutable());
		} else if (set != null) {
			set.remove(pos);
			if (set.isEmpty()) {
				matchesByChunk.remove(key);
			}
		}
	}

	private void onExtract(WorldRenderExtractEvent event) {
		Camera camera = event.context().camera();
		double max = maxDistance.get();
		int drawColor = color.get();
		int ceiling = maxHeight.get();
		Vec3 eye = camera.position();
		// Reject whole chunks on a single centre-to-camera test before touching their contents.
		// A 48-block MaxDistance covers ~9 chunks, but matchesByChunk holds every scanned chunk in
		// render distance -- without this, every match in all of them is visited every frame just
		// to be thrown away.
		double chunkCutoff = max + CHUNK_BOUNDING_RADIUS;
		for (Map.Entry<Long, Set<BlockPos>> entry : matchesByChunk.entrySet()) {
			// Static unpack rather than allocating a ChunkPos -- this runs per chunk, per frame.
			long packed = entry.getKey();
			double dx = ((ChunkPos.getX(packed) << 4) + 8) - eye.x;
			double dz = ((ChunkPos.getZ(packed) << 4) + 8) - eye.z;
			if (dx * dx + dz * dz > chunkCutoff * chunkCutoff) {
				continue;
			}
			for (BlockPos pos : entry.getValue()) {
				// Both checks read the BlockPos directly rather than the AABB, so a rejected
				// position never allocates one -- this runs over every match, every frame.
				if (!Culling.belowHeight(pos.getY(), ceiling)) {
					continue;
				}
				if (!Culling.withinDistance(eye, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, max)) {
					continue;
				}
				AABB box = ShapeBuilder.blockOutline(pos);
				// Frustum-culled inside draw() for the box only -- a tracer's whole point is
				// showing something that isn't in view, so it always draws within distance regardless.
				boolean inFrustum = Culling.isVisible(camera.getCullFrustum(), box);
				EspShapeRenderer.draw(box, drawColor, RenderPass.THROUGH_WALLS, mode.get(), camera, inFrustum);
			}
		}
	}
}

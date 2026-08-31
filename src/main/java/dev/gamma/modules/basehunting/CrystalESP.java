package dev.gamma.modules.basehunting;

import dev.gamma.Gamma;
import dev.gamma.config.setting.ColorSetting;
import dev.gamma.config.setting.DoubleSetting;
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
import dev.gamma.render.ShapeBuilder;
import dev.gamma.util.ColorUtil;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Highlights fully grown amethyst clusters.
 *
 * <p>Only {@code AMETHYST_CLUSTER} counts — the three bud stages are ignored. That distinction is
 * the whole point of the module rather than a detail: a bud only advances to the next stage on a
 * random tick, which requires the chunk to have been within somebody's simulation distance. A geode
 * generates with a mix of stages, so a geode that is <em>all</em> clusters is a geode something has
 * been sitting next to, and a cluster far from any geode is one somebody placed. Both are base
 * signals; the buds are just scenery.
 *
 * <p>Box only, no tracer mode — a tracer answers "where is that one thing", and this is read as a
 * density: how much of what you are looking at has finished growing.
 */
public final class CrystalESP extends Module {

	private static final int CHUNKS_PER_TICK = 4;

	/** Chunk half-diagonal (√2 × 8), so a chunk-level distance reject can't drop one with a corner in range. */
	private static final double CHUNK_BOUNDING_RADIUS = 11.32;

	private static final int FILL_ALPHA = 60;

	private final ColorSetting color = register(new ColorSetting("Color", "Highlight color.", 0xFFB36BFF));
	private final DoubleSetting maxDistance = register(new DoubleSetting("MaxDistance", "Max render distance, in blocks.", 64.0, 8.0, 1024.0));

	private final Map<Long, Set<BlockPos>> matchesByChunk = new ConcurrentHashMap<>();
	private final Deque<ChunkPos> pendingChunks = new ArrayDeque<>();

	private Subscription extractSubscription;
	private Subscription chunkSubscription;
	private Subscription packetSubscription;
	private Subscription tickSubscription;

	public CrystalESP() {
		super("CrystalESP", "Highlights fully grown amethyst clusters through walls.", Category.BASE_HUNTING);
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
		if (Minecraft.getInstance().level == null) {
			return;
		}
		switch (event.packet()) {
			case ClientboundBlockUpdatePacket packet -> updatePosition(packet.getPos(), packet.getBlockState());
			case ClientboundSectionBlocksUpdatePacket packet -> packet.runUpdates(this::updatePosition);
			default -> {
			}
		}
	}

	/** Palette-gated section walk, same shape and same reason as {@code BlockESP.scanChunk}. */
	private void scanChunk(ClientLevel level, LevelChunk chunk) {
		Set<BlockPos> matches = new HashSet<>();
		ChunkPos pos = chunk.getPos();
		Predicate<BlockState> matcher = CrystalESP::isFullyGrown;
		LevelChunkSection[] sections = chunk.getSections();
		for (int index = 0; index < sections.length; index++) {
			LevelChunkSection section = sections[index];
			if (section.hasOnlyAir() || !section.maybeHas(matcher)) {
				continue;
			}
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
			Set<BlockPos> concurrent = ConcurrentHashMap.newKeySet(matches.size());
			concurrent.addAll(matches);
			matchesByChunk.put(pos.pack(), concurrent);
		}
	}

	private static boolean isFullyGrown(BlockState state) {
		return state.is(Blocks.AMETHYST_CLUSTER);
	}

	private void updatePosition(BlockPos pos, BlockState state) {
		long key = ChunkPos.containing(pos).pack();
		Set<BlockPos> set = matchesByChunk.get(key);
		if (isFullyGrown(state)) {
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
		Vec3 eye = camera.position();
		double max = maxDistance.get();
		int stroke = color.get();
		int fill = ColorUtil.withAlpha(stroke, FILL_ALPHA);
		double chunkCutoff = max + CHUNK_BOUNDING_RADIUS;

		for (Map.Entry<Long, Set<BlockPos>> entry : matchesByChunk.entrySet()) {
			long packed = entry.getKey();
			double dx = ((ChunkPos.getX(packed) << 4) + 8) - eye.x;
			double dz = ((ChunkPos.getZ(packed) << 4) + 8) - eye.z;
			if (dx * dx + dz * dz > chunkCutoff * chunkCutoff) {
				continue;
			}
			for (BlockPos pos : entry.getValue()) {
				if (!Culling.withinDistance(eye, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, max)) {
					continue;
				}
				AABB box = ShapeBuilder.blockOutline(pos);
				if (!Culling.isVisible(camera.getCullFrustum(), box)) {
					continue;
				}
				Renderer3D.outlinedBox(box, stroke, fill, RenderPass.THROUGH_WALLS);
			}
		}
	}
}

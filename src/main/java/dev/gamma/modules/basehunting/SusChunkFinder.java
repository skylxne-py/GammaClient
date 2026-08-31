package dev.gamma.modules.basehunting;

import dev.gamma.Gamma;
import dev.gamma.config.setting.BlockListSetting;
import dev.gamma.config.setting.BoolSetting;
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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base-hunting aid distinct from {@code StashFinder}'s storage-density scoring: flags whole chunks
 * whose underground shows traces of a player, rather than scoring how much loot is in them.
 *
 * <p>Two independent kinds of evidence feed one per-chunk count:
 *
 * <ul>
 * <li><b>Blocks that don't belong.</b> The configurable list — planks, glass, wool, torches,
 *     crafting tables. Underground worldgen simply does not produce these, so each one was placed.</li>
 * <li><b>Block states that record time.</b> {@link GrowthSignal} — finished amethyst, mature kelp
 *     and bamboo, berried cave vines, full bee nests, sideways deepslate. Growth only advances on
 *     random ticks, which only fire inside somebody's simulation distance, so a plant that has
 *     finished growing is a measurement of how long that chunk has been kept loaded. That is very
 *     nearly the definition of a base.</li>
 * </ul>
 *
 * <p>Both feed the same threshold on purpose: a real base leaves both kinds of trace, and a chunk
 * with a few of each should clear a bar that neither would clear alone.
 *
 * <p>Same incremental per-chunk scan shape as {@code BlockESP} (project convention: "rescan on block update,
 * not a full sweep every tick"), palette-gated per section, and counting matches per chunk rather
 * than tracking individual positions — only the chunk as a whole gets highlighted, as a filled
 * underground slab rather than per-block boxes.
 */
public final class SusChunkFinder extends Module {

	private static final int CHUNKS_PER_TICK = 4;

	private final BlockListSetting suspiciousBlocks = register(new BlockListSetting("Blocks", "Blocks that almost never occur underground naturally.", List.of(
			Blocks.TORCH, Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS,
			Blocks.COBBLESTONE, Blocks.GLASS, Blocks.WOOL.white(), Blocks.CRAFTING_TABLE,
			Blocks.FURNACE, Blocks.LADDER, Blocks.CHEST)));
	private final IntSetting maxY = register(new IntSetting("MaxY", "Only counts matches at or below this Y level.", 60, -64, 128));
	private final IntSetting threshold = register(new IntSetting("Threshold", "Minimum matched blocks in a chunk before it's flagged.", 4, 1, 64));
	private final IntSetting layerThickness = register(new IntSetting("LayerThickness", "How many blocks thick the highlight is, counting down from MaxY -- not the whole underground column.", 4, 1, 32));
	private final ColorSetting color = register(new ColorSetting("Color", "Highlight fill color.", 0x66FF0000));
	private final IntSetting alpha = register(new IntSetting("Alpha", "Opacity of the highlight, overriding the alpha in Color.", 100, 0, 255));
	private final DoubleSetting maxDistance = register(new DoubleSetting("MaxDistance", "Max render distance, in blocks.", 128.0, 16.0, 1024.0));
	private final IntSetting simulationDistance = register(new IntSetting("SimulationDistance",
			"Chunk radius scanned around you. Named after the simulation distance because that is what the growth signals below actually measure -- a chunk only grows anything while it is inside somebody's simulation distance.", 12, 2, 32));

	// Growth signals: block *states* that record how long a chunk has been ticking, rather than
	// blocks that simply don't belong. See GrowthSignal for what each one is worth. Individually
	// toggleable because their false-positive rates differ enormously by world -- Vines is noise in
	// a jungle and RotatedDeepslate is near-proof everywhere.
	private final BoolSetting amethyst = register(new BoolSetting("Amethyst", "Count fully grown amethyst clusters.", true));
	private final BoolSetting kelp = register(new BoolSetting("Kelp", "Count kelp that has finished growing.", true));
	private final BoolSetting caveVines = register(new BoolSetting("CaveVines", "Count cave vines that have grown glow berries.", true));
	private final BoolSetting vines = register(new BoolSetting("Vines", "Count vines. Noisy in jungles and lush caves.", false));
	private final BoolSetting cocoa = register(new BoolSetting("Cocoa", "Count fully grown cocoa pods.", true));
	private final BoolSetting bamboo = register(new BoolSetting("Bamboo", "Count bamboo that has finished growing.", true));
	private final BoolSetting beeNests = register(new BoolSetting("BeeNests", "Count bee nests full of honey.", true));
	private final BoolSetting rotatedDeepslate = register(new BoolSetting("RotatedDeepslate", "Count sideways deepslate. Worldgen never rotates it, so every match was placed by a player.", true));

	private final Map<Long, Integer> countByChunk = new ConcurrentHashMap<>();
	private final Deque<ChunkPos> pendingChunks = new ArrayDeque<>();

	private Subscription extractSubscription;
	private Subscription chunkSubscription;
	private Subscription packetSubscription;
	private Subscription tickSubscription;

	public SusChunkFinder() {
		super("SusChunkFinder", "Highlights chunks whose underground contains blocks that don't occur there naturally.", Category.BASE_HUNTING);
	}

	@Override
	protected void onEnable() {
		countByChunk.clear();
		pendingChunks.clear();
		Minecraft client = Minecraft.getInstance();
		if (client.level != null && client.player != null) {
			ChunkPos center = ChunkPos.containing(client.player.blockPosition());
			int radius = Math.min(simulationDistance.get(), client.options.renderDistance().get());
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
		countByChunk.clear();
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
	 * Palette-gated section walk, same shape and same reason as {@code BlockESP.scanChunk} — the
	 * old full-column walk was 16×16×(worldHeight) block reads per chunk on the client thread.
	 */
	private void scanChunk(ClientLevel level, LevelChunk chunk) {
		ChunkPos pos = chunk.getPos();
		int limit = Math.min(maxY.get(), level.getMaxY() - 1);
		int count = 0;
		LevelChunkSection[] sections = chunk.getSections();
		for (int index = 0; index < sections.length; index++) {
			LevelChunkSection section = sections[index];
			int baseY = level.getMinY() + (index << 4);
			if (baseY > limit || section.hasOnlyAir() || !section.maybeHas(this::isSuspicious)) {
				continue;
			}
			for (int y = 0; y < 16; y++) {
				if (baseY + y > limit) {
					break;
				}
				for (int x = 0; x < 16; x++) {
					for (int z = 0; z < 16; z++) {
						if (isSuspicious(section.getBlockState(x, y, z))) {
							count++;
						}
					}
				}
			}
		}
		if (count == 0) {
			countByChunk.remove(pos.pack());
		} else {
			countByChunk.put(pos.pack(), count);
		}
	}

	/**
	 * A block is suspicious either because it doesn't belong underground at all (the block list) or
	 * because its state records time spent ticking (the growth signals). Both feed the same
	 * per-chunk count, so a chunk with a few of each still clears the threshold — which is the
	 * point, since a real base leaves both kinds of trace.
	 */
	private boolean isSuspicious(BlockState state) {
		if (suspiciousBlocks.contains(state.getBlock())) {
			return true;
		}
		return (amethyst.get() && GrowthSignal.isFullyGrownAmethyst(state))
				|| (kelp.get() && GrowthSignal.isMatureKelp(state))
				|| (caveVines.get() && GrowthSignal.isBerriedCaveVine(state))
				|| (vines.get() && GrowthSignal.isVine(state))
				|| (cocoa.get() && GrowthSignal.isFullyGrownCocoa(state))
				|| (bamboo.get() && GrowthSignal.isMatureBamboo(state))
				|| (beeNests.get() && GrowthSignal.isFullBeeNest(state))
				|| (rotatedDeepslate.get() && GrowthSignal.isRotatedDeepslate(state));
	}

	/** Individual matched positions aren't tracked (only a per-chunk count), so a single block update re-scans that one chunk's underground column rather than trying to increment/decrement in place. */
	private void updatePosition(BlockPos pos, BlockState state) {
		if (pos.getY() > maxY.get()) {
			return;
		}
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}
		ChunkPos chunkPos = ChunkPos.containing(pos);
		if (level.hasChunk(chunkPos.x(), chunkPos.z())) {
			scanChunk(level, level.getChunk(chunkPos.x(), chunkPos.z()));
		}
	}

	private void onExtract(WorldRenderExtractEvent event) {
		ClientLevel level = event.context().level();
		Camera camera = event.context().camera();
		double max = maxDistance.get();
		// Alpha overrides whatever the ColorSetting carries, so the slider is the single
		// control for opacity rather than fighting the picker's own alpha strip.
		int drawColor = ColorUtil.withAlpha(color.get(), alpha.get());
		int limit = Math.min(maxY.get(), level.getMaxY() - 1);
		int needed = threshold.get();

		for (Map.Entry<Long, Integer> entry : countByChunk.entrySet()) {
			if (entry.getValue() < needed) {
				continue;
			}
			ChunkPos pos = ChunkPos.unpack(entry.getKey());
			int layerBottom = Math.max(level.getMinY(), limit + 1 - layerThickness.get());
			AABB box = new AABB(pos.getMinBlockX(), layerBottom, pos.getMinBlockZ(), pos.getMinBlockX() + 16, limit + 1, pos.getMinBlockZ() + 16);
			if (!Culling.shouldRender(camera.getCullFrustum(), box, camera.position(), max)) {
				continue;
			}
			Renderer3D.filledBox(box, drawColor, RenderPass.THROUGH_WALLS);
		}
	}
}

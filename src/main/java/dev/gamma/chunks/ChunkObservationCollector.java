package dev.gamma.chunks;

import dev.gamma.Gamma;
import dev.gamma.chunks.classifier.BaselineStore;
import dev.gamma.chunks.classifier.ClassifierPipeline;
import dev.gamma.chunks.classifier.PaletteEntropy;
import dev.gamma.chunks.db.ChunkDatabase;
import dev.gamma.chunks.model.ChunkObservation;
import dev.gamma.chunks.model.ClassificationResult;
import dev.gamma.config.ConfigManager;
import dev.gamma.core.event.EventBus;
import dev.gamma.core.event.events.ChunkReceiveEvent;
import dev.gamma.core.event.events.PacketReceiveEvent;
import dev.gamma.core.event.events.TickEvent;
import dev.gamma.core.event.events.WorldLoadEvent;
import dev.gamma.core.event.events.WorldUnloadEvent;
import dev.gamma.modules.world.NewChunks;
import dev.gamma.modules.world.StashFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The always-on engine behind chunk logging: every chunk data packet received gets recorded,
 * by design, not something a module toggles. {@link dev.gamma.modules.world.NewChunks} is
 * the opt-in *presentation* layer on top of this — this class runs for the lifetime of a world
 * connection and feeds every logged chunk into whichever server's {@link ChunkDatabase}.
 *
 * <p>Three event sources, two different threads (see {@link PacketReceiveEvent}'s own doc comment
 * — it fires on the network thread, everything else here on the client thread), stitched
 * together per chunk position:
 * <ul>
 *   <li>{@link TickEvent} tracks render-distance-edge chunk positions as "expected" (proxy for a
 *       request, since vanilla never sends an explicit per-chunk request) — the basis for the
 *       generation-latency signal — and sweeps finalized observations.</li>
 *   <li>{@link PacketReceiveEvent} catches the raw {@code ClientboundLevelChunkWithLightPacket}
 *       (packet size, light coverage) before it's applied, and afterwards counts block-update
 *       packets landing on chunks still inside their post-load observation window.</li>
 *   <li>{@link ChunkReceiveEvent} fires once the chunk is actually in the level: this is where
 *       the one-time per-chunk snapshot (palette entropy, block-entity/notable-block counts,
 *       unrolled-loot containers) is taken and the observation window opens.</li>
 * </ul>
 */
public final class ChunkObservationCollector {

	/**
	 * the map overlay ({@code gui.map.MapTileCache}) needs to reach this collector's live
	 * {@link ChunkDatabase} with no constructor-injection path (it's built from a {@code
	 * HudComponent}/{@code Screen}, neither of which takes core-service dependencies) — same
	 * static-reachability seam already used the other direction for {@code NewChunks}/{@code
	 * StashFinder} (see the design notes).
	 */
	public static volatile ChunkObservationCollector instance;

	private static final long POST_LOAD_WINDOW_MILLIS = 3000;
	private static final long STALE_PENDING_MILLIS = 60_000;
	private static final int STALE_SWEEP_INTERVAL_TICKS = 200;

	private final EventBus eventBus;
	private final BaselineStore baselines = new BaselineStore();
	private final ClassifierPipeline pipeline = ClassifierPipeline.standard();

	private final Map<Long, Long> pendingRequestTimes = new ConcurrentHashMap<>();
	private final Map<Long, PacketMetrics> pendingPacketMetrics = new ConcurrentHashMap<>();
	private final Map<Long, InFlightObservation> inFlight = new ConcurrentHashMap<>();

	private volatile ChunkDatabase database;
	private volatile String currentServer;
	private ChunkPos lastPlayerChunkPos;
	private int tickCounter;

	private volatile FixtureRecorder fixtureRecorder;

	public ChunkObservationCollector(EventBus eventBus) {
		this.eventBus = eventBus;
		instance = this;
	}

	/** Never unsubscribed — this runs for the client's whole lifetime, same as {@link dev.gamma.core.event.FabricEventBridge}. */
	public void install() {
		eventBus.subscribe(WorldLoadEvent.class, this::onWorldLoad);
		eventBus.subscribe(WorldUnloadEvent.class, this::onWorldUnload);
		eventBus.subscribe(ChunkReceiveEvent.class, this::onChunkReceive);
		eventBus.subscribe(PacketReceiveEvent.class, this::onPacketReceive);
		eventBus.subscribe(TickEvent.class, this::onTick);
	}

	public void setFixtureRecorder(FixtureRecorder recorder) {
		this.fixtureRecorder = recorder;
	}

	/** Null until a world/server is active — {@code .chunks stats}/{@code .chunks query} no-op gracefully until then. */
	public ChunkDatabase database() {
		return database;
	}

	private void onWorldLoad(WorldLoadEvent event) {
		pendingRequestTimes.clear();
		pendingPacketMetrics.clear();
		inFlight.clear();
		lastPlayerChunkPos = null;
		currentServer = ConfigManager.currentProfileKey(Minecraft.getInstance());
		database = new ChunkDatabase(currentServer);
	}

	private void onWorldUnload(WorldUnloadEvent event) {
		if (database != null) {
			database.close();
		}
		database = null;
		currentServer = null;
		pendingRequestTimes.clear();
		pendingPacketMetrics.clear();
		inFlight.clear();
	}

	private void onTick(TickEvent event) {
		if (event.phase() != TickEvent.Phase.END) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = client.level;
		if (level == null || database == null) {
			return;
		}
		expandTracking(client, level);
		finalizeReady();
		tickCounter++;
		if (tickCounter % STALE_SWEEP_INTERVAL_TICKS == 0) {
			sweepStale();
		}
	}

	/** Marks render-distance-edge chunk positions not yet loaded as "expected" — the proxy for a request time, since vanilla never sends one explicitly. */
	private void expandTracking(Minecraft client, ClientLevel level) {
		if (client.player == null) {
			return;
		}
		ChunkPos center = ChunkPos.containing(client.player.blockPosition());
		if (center.equals(lastPlayerChunkPos)) {
			return;
		}
		lastPlayerChunkPos = center;
		int radius = client.options.renderDistance().get();
		long now = System.currentTimeMillis();
		for (int cx = center.x() - radius; cx <= center.x() + radius; cx++) {
			for (int cz = center.z() - radius; cz <= center.z() + radius; cz++) {
				if (level.hasChunk(cx, cz)) {
					continue;
				}
				pendingRequestTimes.putIfAbsent(ChunkPos.pack(cx, cz), now);
			}
		}
	}

	private void onPacketReceive(PacketReceiveEvent event) {
		switch (event.packet()) {
			case ClientboundLevelChunkWithLightPacket packet -> onLevelChunkPacket(packet);
			case ClientboundBlockUpdatePacket packet -> onBlockUpdate(packet.getPos(), packet.getBlockState());
			case ClientboundSectionBlocksUpdatePacket packet -> packet.runUpdates(this::onBlockUpdate);
			default -> {
			}
		}
	}

	private void onLevelChunkPacket(ClientboundLevelChunkWithLightPacket packet) {
		int packetSize = packet.getChunkData().getReadBuffer().readableBytes();
		int lightCoverage = packet.getLightData().getSkyYMask().cardinality() + packet.getLightData().getBlockYMask().cardinality();
		long key = ChunkPos.pack(packet.getX(), packet.getZ());
		pendingPacketMetrics.put(key, new PacketMetrics(packetSize, lightCoverage));
	}

	private void onBlockUpdate(BlockPos pos, BlockState state) {
		InFlightObservation observation = inFlight.get(ChunkPos.pack(pos));
		if (observation == null) {
			return;
		}
		observation.totalUpdateCount.incrementAndGet();
		if (!state.getFluidState().isEmpty() && !state.getFluidState().isSource()) {
			observation.fluidUpdateCount.incrementAndGet();
		}
	}

	private void onChunkReceive(ChunkReceiveEvent event) {
		if (database == null || currentServer == null) {
			return;
		}
		ChunkPos pos = event.chunk().getPos();
		long key = pos.pack();
		long now = System.currentTimeMillis();

		Long requestedAt = pendingRequestTimes.remove(key);
		long requestLatency = requestedAt == null ? -1 : now - requestedAt;
		PacketMetrics metrics = pendingPacketMetrics.remove(key);

		ChunkSnapshot snapshot = snapshot(event.chunk());
		String dimension = event.level().dimension().identifier().toString();

		InFlightObservation inFlightObservation = new InFlightObservation(
				currentServer, dimension, pos.x(), pos.z(), now, requestLatency,
				metrics == null ? 0 : metrics.packetSizeBytes(),
				metrics == null ? 0 : metrics.lightCoverageSignal(),
				snapshot, now + POST_LOAD_WINDOW_MILLIS);
		inFlight.put(key, inFlightObservation);
	}

	private void finalizeReady() {
		if (inFlight.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		for (var entry : inFlight.entrySet()) {
			InFlightObservation observation = entry.getValue();
			if (now < observation.finalizeAtMillis) {
				continue;
			}
			inFlight.remove(entry.getKey(), observation);
			finalizeObservation(observation);
		}
	}

	private void finalizeObservation(InFlightObservation in) {
		long contentHash = ContentHash.compute(in.snapshot.blockEntityCounts(), in.snapshot.notableBlockCounts(),
				in.snapshot.paletteEntropy(), in.packetSizeBytes);
		ChunkObservation observation = new ChunkObservation(
				in.server, in.dimension, in.chunkX, in.chunkZ, in.loadTimeMillis, in.requestLatencyMillis,
				in.packetSizeBytes, in.lightCoverageSignal, in.snapshot.paletteEntropy(),
				in.fluidUpdateCount.get(), in.totalUpdateCount.get(),
				in.snapshot.unrolledLootContainers(), in.snapshot.totalContainers(),
				in.snapshot.blockEntityCounts(), in.snapshot.notableBlockCounts(), contentHash);

		ClassificationResult result = pipeline.classify(observation, baselines);
		ChunkDatabase db = database;
		if (db != null) {
			db.upsert(observation, result);
		}
		FixtureRecorder recorder = fixtureRecorder;
		if (recorder != null) {
			recorder.record(observation, result);
		}
		NewChunks module = NewChunks.instance;
		if (module != null) {
			module.onClassified(observation.dimension(), observation.chunkX(), observation.chunkZ(), result);
		}
		StashFinder stashFinder = StashFinder.instance;
		if (stashFinder != null) {
			stashFinder.onClassified(observation, result, db);
		}
	}

	private void sweepStale() {
		long cutoff = System.currentTimeMillis() - STALE_PENDING_MILLIS;
		pendingRequestTimes.values().removeIf(time -> time < cutoff);
		// Packet metrics with no matching ChunkReceiveEvent (e.g. the chunk was immediately
		// unloaded again) would otherwise sit here forever; nothing timestamps them individually,
		// so once pending count gets large just drop everything — cheap to re-learn on the next packet.
		if (pendingPacketMetrics.size() > 4096) {
			pendingPacketMetrics.clear();
		}
	}

	private ChunkSnapshot snapshot(LevelChunk chunk) {
		Map<BlockState, Integer> stateCounts = new HashMap<>();
		for (LevelChunkSection section : chunk.getSections()) {
			if (section.hasOnlyAir()) {
				continue;
			}
			section.getStates().count((state, count) -> stateCounts.merge(state, count, Integer::sum));
		}
		double entropy = PaletteEntropy.normalizedShannonEntropy(
				stateCounts.values().stream().mapToInt(Integer::intValue).toArray());

		List<Block> notableBlocks = NewChunks.instance != null ? NewChunks.instance.notableBlocks().get() : List.of();
		Map<String, Integer> notableBlockCounts = new HashMap<>();
		for (var entry : stateCounts.entrySet()) {
			Block block = entry.getKey().getBlock();
			if (notableBlocks.contains(block)) {
				notableBlockCounts.merge(BuiltInRegistries.BLOCK.getKey(block).toString(), entry.getValue(), Integer::sum);
			}
		}

		Map<String, Integer> blockEntityCounts = new HashMap<>();
		int unrolledLoot = 0;
		int totalContainers = 0;
		for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
			String type = blockEntityTypeName(blockEntity);
			if (type != null) {
				blockEntityCounts.merge(type, 1, Integer::sum);
			}
			if (blockEntity instanceof RandomizableContainerBlockEntity container) {
				totalContainers++;
				if (container.getLootTable() != null) {
					unrolledLoot++;
				}
			}
		}

		return new ChunkSnapshot(entropy, blockEntityCounts, notableBlockCounts, unrolledLoot, totalContainers);
	}

	private static String blockEntityTypeName(BlockEntity blockEntity) {
		return switch (blockEntity) {
			case TrappedChestBlockEntity ignored -> "trapped_chest";
			case ChestBlockEntity ignored -> "chest";
			case EnderChestBlockEntity ignored -> "ender_chest";
			case BarrelBlockEntity ignored -> "barrel";
			case ShulkerBoxBlockEntity ignored -> "shulker_box";
			case HopperBlockEntity ignored -> "hopper";
			case FurnaceBlockEntity ignored -> "furnace";
			case DropperBlockEntity ignored -> "dropper";
			case DispenserBlockEntity ignored -> "dispenser";
			case BrewingStandBlockEntity ignored -> "brewing_stand";
			default -> null;
		};
	}

	private record PacketMetrics(int packetSizeBytes, int lightCoverageSignal) {
	}

	private record ChunkSnapshot(double paletteEntropy, Map<String, Integer> blockEntityCounts,
			Map<String, Integer> notableBlockCounts, int unrolledLootContainers, int totalContainers) {
	}

	/** Mutable accumulator for the {@link #POST_LOAD_WINDOW_MILLIS} following a chunk's load. */
	private static final class InFlightObservation {
		final String server;
		final String dimension;
		final int chunkX;
		final int chunkZ;
		final long loadTimeMillis;
		final long requestLatencyMillis;
		final int packetSizeBytes;
		final int lightCoverageSignal;
		final ChunkSnapshot snapshot;
		final long finalizeAtMillis;
		final AtomicInteger fluidUpdateCount = new AtomicInteger();
		final AtomicInteger totalUpdateCount = new AtomicInteger();

		InFlightObservation(String server, String dimension, int chunkX, int chunkZ, long loadTimeMillis,
				long requestLatencyMillis, int packetSizeBytes, int lightCoverageSignal, ChunkSnapshot snapshot,
				long finalizeAtMillis) {
			this.server = server;
			this.dimension = dimension;
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
			this.loadTimeMillis = loadTimeMillis;
			this.requestLatencyMillis = requestLatencyMillis;
			this.packetSizeBytes = packetSizeBytes;
			this.lightCoverageSignal = lightCoverageSignal;
			this.snapshot = snapshot;
			this.finalizeAtMillis = finalizeAtMillis;
		}
	}
}

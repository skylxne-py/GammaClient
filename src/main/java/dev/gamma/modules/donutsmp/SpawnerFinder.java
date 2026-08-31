package dev.gamma.modules.donutsmp;

import dev.gamma.Gamma;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.ColorSetting;
import dev.gamma.config.setting.DoubleSetting;
import dev.gamma.config.setting.EnumSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.core.event.Subscription;
import dev.gamma.core.event.events.ChunkReceiveEvent;
import dev.gamma.core.event.events.PacketReceiveEvent;
import dev.gamma.core.event.events.TickEvent;
import dev.gamma.core.event.events.WorldRenderExtractEvent;
import dev.gamma.core.event.events.WorldUnloadEvent;
import dev.gamma.modules.misc.FakeCoordinates;
import dev.gamma.render.Culling;
import dev.gamma.render.EspMode;
import dev.gamma.render.EspShapeRenderer;
import dev.gamma.render.RenderPass;
import dev.gamma.render.Renderer3D;
import dev.gamma.render.ShapeBuilder;
import dev.gamma.util.NotificationSound;
import dev.gamma.util.SoundNotifier;
import dev.gamma.util.ToastNotifier;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spawner ESP with a label and a chime — the same scan/box shape as {@link CrystalESP}, plus the
 * two things a spawner specifically wants: what it spawns, and how many are in the block.
 *
 * <h2>The mob it spawns</h2>
 *
 * <p>Read from the spawner's own display entity — the one vanilla spins inside the cage — via
 * {@code BaseSpawner.getOrCreateDisplayEntity}. That is real synced data, not a guess: the server
 * has to tell the client what to draw in there. Resolved once per spawner on the tick thread and
 * cached, rather than per frame, because creating the display entity is the expensive half.
 *
 * <h2>The stack count</h2>
 *
 * <p>Servers that let several spawners share a block keep that count in plugin storage, which is
 * never sent to a client — there is nothing in the block entity to read. The one place the number
 * genuinely reaches you is the title of the GUI the server opens when you right-click one, which
 * is an ordinary screen title like any other. So that is where it is taken from: open a stacked
 * spawner once, and the count parsed out of that title is remembered for that position for as long
 * as the world stays loaded.
 *
 * <p>Everything else shows {@code x1}, which is the honest answer rather than a placeholder — a
 * spawner you have not opened is a spawner whose count you do not know, and on a server without
 * stacking it is also the correct one. The label always carries the mob name, which needs no
 * interaction at all.
 */
public final class SpawnerFinder extends Module {

	private static final int CHUNKS_PER_TICK = 4;

	/** Chunk half-diagonal (√2 × 8), so a chunk-level distance reject can't drop one with a corner in range. */
	private static final double CHUNK_BOUNDING_RADIUS = 11.32;

	/** First run of digits in the container title, e.g. "Spawner (x12)" or "12x Zombie Spawner". */
	private static final Pattern COUNT_IN_TITLE = Pattern.compile("(\\d+)");

	private final ColorSetting color = register(new ColorSetting("Color", "Highlight color.", 0xFFFFC24D));
	private final DoubleSetting maxDistance = register(new DoubleSetting("MaxDistance", "Max render distance, in blocks.", 96.0, 8.0, 1024.0));
	private final EnumSetting<EspMode> mode = register(new EnumSetting<>("Mode", "Box / tracer / both.", EspMode.class, EspMode.BOX));
	private final BoolSetting label = register(new BoolSetting("Label", "Draw a floating label above each spawner with its stack count and what it spawns.", true));
	private final BoolSetting sound = register(new BoolSetting("Sound", "Play a chime the first time a spawner comes into view.", true));
	private final DoubleSetting soundVolume = register(new DoubleSetting("SoundVolume", "Chime volume. 1 is as loud as the game will play a UI sound; past that it is the vanilla Master/UI sliders doing the limiting, not this.", 1.0, 0.0, SoundNotifier.MAX_VOLUME));
	private final EnumSetting<NotificationSound> soundSample = register(new EnumSetting<>("SoundSample", "Which sample the chime uses.", NotificationSound.class, NotificationSound.AMETHYST));
	private final DoubleSetting soundPitch = register(new DoubleSetting("SoundPitch", "Playback pitch. Higher is brighter and more obviously a notification.", 1.0, 0.5, 2.0));
	private final BoolSetting toast = register(new BoolSetting("Toast", "Show a notification above the hotbar, with the coordinates, the first time a spawner comes into view.", true));

	private final Map<Long, Set<BlockPos>> matchesByChunk = new ConcurrentHashMap<>();
	private final Map<BlockPos, String> mobNames = new ConcurrentHashMap<>();
	private final Map<BlockPos, Integer> stackCounts = new ConcurrentHashMap<>();
	private final Set<BlockPos> announced = ConcurrentHashMap.newKeySet();
	private final Deque<ChunkPos> pendingChunks = new ArrayDeque<>();
	/** Positions still needing a mob name. Drained on tick because {@code updatePosition} can arrive off the client thread. */
	private final Queue<BlockPos> pendingNames = new ConcurrentLinkedQueue<>();
	private final SoundNotifier chime = new SoundNotifier(500);
	private final ToastNotifier toasts = new ToastNotifier(2_000);

	/** The spawner the crosshair was on when the last container screen opened — see the class doc. */
	private BlockPos lastLookedAtSpawner;
	private Screen lastInspectedScreen;

	private Subscription extractSubscription;
	private Subscription chunkSubscription;
	private Subscription packetSubscription;
	private Subscription tickSubscription;
	private Subscription worldUnloadSubscription;

	public SpawnerFinder() {
		super("SpawnerFinder", "Highlights spawners through walls, with a label for the mob and stack count.", Category.DONUT_SMP);
	}

	@Override
	protected void onEnable() {
		clearState();
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
		// Stack counts and mob names are keyed by position, which only means anything within one
		// world -- the same coordinates in the next dimension are a different block entirely.
		worldUnloadSubscription = listen(WorldUnloadEvent.class, event -> clearState());
	}

	@Override
	protected void onDisable() {
		Gamma.EVENT_BUS.unsubscribe(extractSubscription);
		Gamma.EVENT_BUS.unsubscribe(chunkSubscription);
		Gamma.EVENT_BUS.unsubscribe(packetSubscription);
		Gamma.EVENT_BUS.unsubscribe(tickSubscription);
		Gamma.EVENT_BUS.unsubscribe(worldUnloadSubscription);
		clearState();
	}

	private void clearState() {
		matchesByChunk.clear();
		mobNames.clear();
		stackCounts.clear();
		announced.clear();
		pendingChunks.clear();
		pendingNames.clear();
		lastLookedAtSpawner = null;
		lastInspectedScreen = null;
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
		BlockPos pending;
		while ((pending = pendingNames.poll()) != null) {
			resolveMobName(client.level, pending);
		}
		trackContainerTitle(client);
	}

	/**
	 * Remembers the spawner under the crosshair while no screen is open, then reads the count out
	 * of the title of whatever screen the server opens next. Guarded on the screen <em>instance</em>
	 * so a title is parsed once per opening rather than every tick it stays open.
	 */
	private void trackContainerTitle(Minecraft client) {
		Screen screen = client.gui.screen();
		if (screen == null) {
			lastInspectedScreen = null;
			if (client.hitResult instanceof BlockHitResult hit
					&& client.hitResult.getType() == HitResult.Type.BLOCK
					&& client.level != null
					&& client.level.getBlockState(hit.getBlockPos()).is(Blocks.SPAWNER)) {
				lastLookedAtSpawner = hit.getBlockPos().immutable();
			}
			return;
		}
		if (screen == lastInspectedScreen || !(screen instanceof AbstractContainerScreen<?>) || lastLookedAtSpawner == null) {
			return;
		}
		lastInspectedScreen = screen;
		Matcher matcher = COUNT_IN_TITLE.matcher(screen.getTitle().getString());
		if (matcher.find()) {
			try {
				int count = Integer.parseInt(matcher.group(1));
				if (count > 0) {
					stackCounts.put(lastLookedAtSpawner, count);
				}
			} catch (NumberFormatException e) {
				// A title with a number too long to be a count is a title that wasn't one.
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

	/** Palette-gated section walk, same shape and same reason as {@code CrystalESP.scanChunk}. */
	private void scanChunk(ClientLevel level, LevelChunk chunk) {
		Set<BlockPos> matches = new HashSet<>();
		ChunkPos pos = chunk.getPos();
		Predicate<BlockState> matcher = SpawnerFinder::isSpawner;
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
			return;
		}
		Set<BlockPos> concurrent = ConcurrentHashMap.newKeySet(matches.size());
		concurrent.addAll(matches);
		matchesByChunk.put(pos.pack(), concurrent);
		for (BlockPos match : matches) {
			pendingNames.add(match);
			if (announced.add(match)) {
				announce(match);
			}
		}
	}

	/**
	 * Both notifications for one newly seen spawner, in one place — a chunk scan and a live block
	 * update are separate discovery paths that must not drift apart in what they report.
	 *
	 * <p>Coordinates only, deliberately: what the spawner spawns is resolved lazily on the tick
	 * thread ({@link #resolveMobName}) and is not known yet at the moment it is first seen. Waiting
	 * for the name would mean the notification arrives after the thing it is about.
	 */
	private void announce(BlockPos pos) {
		if (sound.get()) {
			chime.play(soundSample.get().event(), soundPitch.get().floatValue(), soundVolume.get());
		}
		if (toast.get()) {
			// Through FakeCoordinates, so a disguised readout isn't undone by a find announcing where
			// you actually are two seconds later.
			toasts.show("Spawner found", FakeCoordinates.describe(pos.getX(), pos.getY(), pos.getZ(), true));
		}
	}

	/**
	 * Caches what a spawner spawns. Runs on the tick thread and only once per position: the display
	 * entity is built lazily by vanilla and is the costly part of asking.
	 */
	private void resolveMobName(ClientLevel level, BlockPos pos) {
		if (mobNames.containsKey(pos)) {
			return;
		}
		if (!(level.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner)) {
			return;
		}
		Entity display = spawner.getSpawner().getOrCreateDisplayEntity(level, pos);
		if (display != null) {
			mobNames.put(pos.immutable(), display.getType().getDescription().getString());
		}
	}

	private static boolean isSpawner(BlockState state) {
		return state.is(Blocks.SPAWNER);
	}

	private void updatePosition(BlockPos pos, BlockState state) {
		long key = ChunkPos.containing(pos).pack();
		Set<BlockPos> set = matchesByChunk.get(key);
		if (isSpawner(state)) {
			if (set == null) {
				set = ConcurrentHashMap.newKeySet();
				matchesByChunk.put(key, set);
			}
			BlockPos immutable = pos.immutable();
			set.add(immutable);
			pendingNames.add(immutable);
			if (announced.add(immutable)) {
				announce(immutable);
			}
		} else if (set != null) {
			set.remove(pos);
			mobNames.remove(pos);
			stackCounts.remove(pos);
			announced.remove(pos);
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
		double chunkCutoff = max + CHUNK_BOUNDING_RADIUS;
		boolean drawLabels = label.get();
		EspMode espMode = mode.get();

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
				// Not a `continue`: a tracer's whole job is pointing at something off screen, so
				// EspShapeRenderer takes the frustum result and applies it to the box only.
				boolean inFrustum = Culling.isVisible(camera.getCullFrustum(), box);
				EspShapeRenderer.draw(box, stroke, RenderPass.THROUGH_WALLS, espMode, camera, inFrustum);
				if (drawLabels && inFrustum) {
					Renderer3D.text3d(new Vec3(pos.getX() + 0.5, pos.getY() + 1.4, pos.getZ() + 0.5), labelFor(pos), stroke, RenderPass.THROUGH_WALLS);
				}
			}
		}
	}

	private String labelFor(BlockPos pos) {
		String mob = mobNames.get(pos);
		String count = "Spawner x" + stackCounts.getOrDefault(pos, 1);
		return mob == null ? count : count + " - " + mob;
	}
}

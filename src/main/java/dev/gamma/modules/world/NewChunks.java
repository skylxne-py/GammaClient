package dev.gamma.modules.world;

import dev.gamma.Gamma;
import dev.gamma.chunks.model.Classification;
import dev.gamma.chunks.model.ClassificationResult;
import dev.gamma.config.setting.BlockListSetting;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.ColorSetting;
import dev.gamma.config.setting.DoubleSetting;
import dev.gamma.config.setting.IntSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.core.event.Subscription;
import dev.gamma.core.event.events.WorldRenderExtractEvent;
import dev.gamma.render.Culling;
import dev.gamma.render.RenderPass;
import dev.gamma.render.Renderer3D;
import dev.gamma.util.ColorUtil;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The presentation half of Phase 5 — {@link dev.gamma.chunks.ChunkObservationCollector} is the
 * always-on logging engine (roadmap: "every chunk data packet received gets recorded"); this
 * module only draws whatever it's told about via {@link #onClassified}, and owns the
 * user-configurable notable-block list the collector reads back through {@link #notableBlocks()}
 * (mirrors the {@code Xray}/{@code Fullbright}-style static-instance seam documented in
 * the design notes Phase 4 — the collector is a core service, not wired through the
 * event bus, so it has no other way to reach a live module's settings).
 */
public final class NewChunks extends Module {

	private static final int MAX_CACHED_CHUNKS = 8192;

	public static volatile NewChunks instance;

	private final IntSetting radius = register(new IntSetting("Radius", "How many chunks out to draw, in each direction.", 8, 1, 32));
	private final DoubleSetting minConfidence = register(new DoubleSetting(
			"MinConfidence", "Only draw a chunk when the classifier is at least this sure about it. Lower shows more chunks and more guesses.", 0.75, 0.5, 1.0));
	private final BoolSetting showExisting = register(new BoolSetting("ShowExisting", "Also draw chunks classified as already explored.", false));
	private final BoolSetting showUnknown = register(new BoolSetting("ShowUnknown", "Also draw chunks the classifiers couldn't call either way.", false));
	private final IntSetting displayY = register(new IntSetting("DisplayY", "Y level to draw the markers at.", 64, -64, 320));
	private final IntSetting thickness = register(new IntSetting("Thickness", "How many blocks thick each marker is.", 2, 1, 16));
	private final DoubleSetting maxDistance = register(new DoubleSetting("MaxDistance", "Max render distance, in blocks.", 256.0, 16.0, 1024.0));
	private final ColorSetting newColor = register(new ColorSetting("NewColor", "Color for likely-new chunks.", 0x9933DD77));
	private final ColorSetting existingColor = register(new ColorSetting("ExistingColor", "Color for likely-existing chunks.", 0x99888888));
	private final ColorSetting unknownColor = register(new ColorSetting("UnknownColor", "Color for chunks the classifiers couldn't call.", 0x99CCCC33));
	private final BlockListSetting notableBlocks = register(new BlockListSetting("NotableBlocks", "Blocks whose per-chunk counts get logged alongside storage counts.",
			List.of(Blocks.SPAWNER, Blocks.BEACON, Blocks.ANCIENT_DEBRIS, Blocks.NETHERITE_BLOCK, Blocks.ENCHANTING_TABLE, Blocks.END_PORTAL_FRAME)));

	private final Map<ChunkKey, CachedClassification> classifiedChunks = new LinkedHashMap<>(256, 0.75f, false) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<ChunkKey, CachedClassification> eldest) {
			return size() > MAX_CACHED_CHUNKS;
		}
	};

	private Subscription extractSubscription;

	public NewChunks() {
		super("NewChunks", "Renders logged chunks color-coded by new-chunk classification.", Category.WORLD);
		instance = this;
	}

	public BlockListSetting notableBlocks() {
		return notableBlocks;
	}

	/** Called by {@link dev.gamma.chunks.ChunkObservationCollector} for every finalized observation, regardless of whether this module is enabled — logging never stops, drawing does. */
	public void onClassified(String dimension, int chunkX, int chunkZ, ClassificationResult result) {
		classifiedChunks.put(new ChunkKey(dimension, ChunkPos.pack(chunkX, chunkZ)), new CachedClassification(result.classification(), result.confidence()));
	}

	@Override
	protected void onEnable() {
		extractSubscription = listen(WorldRenderExtractEvent.class, this::onExtract);
	}

	@Override
	protected void onDisable() {
		Gamma.EVENT_BUS.unsubscribe(extractSubscription);
	}

	private void onExtract(WorldRenderExtractEvent event) {
		ClientLevel level = event.context().level();
		Camera camera = event.context().camera();
		String dimension = level.dimension().identifier().toString();
		ChunkPos center = ChunkPos.containing(camera.blockPosition());
		int r = radius.get();
		double minimum = minConfidence.get();
		double max = maxDistance.get();

		int bottom = Math.max(level.getMinY(), Math.min(displayY.get(), level.getMaxY() - 1));
		int top = Math.min(level.getMaxY(), bottom + thickness.get());

		for (Map.Entry<ChunkKey, CachedClassification> entry : classifiedChunks.entrySet()) {
			ChunkKey key = entry.getKey();
			if (!key.dimension().equals(dimension)) {
				continue;
			}
			int x = ChunkPos.getX(key.packedPos());
			int z = ChunkPos.getZ(key.packedPos());
			if (Math.abs(x - center.x()) > r || Math.abs(z - center.z()) > r) {
				continue;
			}
			CachedClassification cached = entry.getValue();
			if (!shouldShow(cached, minimum)) {
				continue;
			}
			AABB box = new AABB(x << 4, bottom, z << 4, (x << 4) + 16, top, (z << 4) + 16);
			if (!Culling.shouldRender(camera.getCullFrustum(), box, camera.position(), max)) {
				continue;
			}
			draw(box, cached);
		}
	}

	/**
	 * The old filter only hid chunks near a 50/50 split and let every {@code UNKNOWN} through
	 * unconditionally, so in practice almost everything the collector had ever seen was drawn.
	 * A classification you aren't confident about is noise on screen, so confidence is now a
	 * floor rather than a nudge, and the two categories that aren't "this looks freshly
	 * generated" are opt-in.
	 */
	private boolean shouldShow(CachedClassification cached, double minimum) {
		return switch (cached.classification()) {
			case LIKELY_NEW -> cached.confidence() >= minimum;
			case LIKELY_EXISTING -> showExisting.get() && (1.0 - cached.confidence()) >= minimum;
			case UNKNOWN -> showUnknown.get();
		};
	}

	/**
	 * A flat slab rather than the old bedrock-to-build-limit wireframe. That box was ~400 blocks
	 * tall, so from anywhere near ground level a screenful of them was just a thicket of vertical
	 * lines with no readable shape — and chunk classification is map-like information, best read
	 * looking down at a single plane. Same presentation {@code SusChunkFinder} uses, for the same
	 * reason. Filled for the area, outlined so neighbouring chunks of the same class stay
	 * individually countable instead of merging into one blob.
	 */
	private void draw(AABB box, CachedClassification cached) {
		int baseColor = switch (cached.classification()) {
			case LIKELY_NEW -> newColor.get();
			case LIKELY_EXISTING -> existingColor.get();
			case UNKNOWN -> unknownColor.get();
		};
		// Confidence still modulates alpha, so a marginal call reads as fainter than a certain
		// one -- but only across the band that survives the MinConfidence floor.
		double certainty = switch (cached.classification()) {
			case LIKELY_NEW -> cached.confidence();
			case LIKELY_EXISTING -> 1.0 - cached.confidence();
			case UNKNOWN -> 0.5;
		};
		int fill = ColorUtil.scaleAlpha(baseColor, Math.max(0.35, certainty));
		Renderer3D.outlinedBox(box, ColorUtil.withAlpha(baseColor, 0xFF), fill, RenderPass.THROUGH_WALLS);
	}

	private record ChunkKey(String dimension, long packedPos) {
	}

	private record CachedClassification(Classification classification, double confidence) {
	}
}

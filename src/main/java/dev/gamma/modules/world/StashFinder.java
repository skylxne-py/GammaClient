package dev.gamma.modules.world;

import dev.gamma.chunks.ChunkObservationCollector;
import dev.gamma.chunks.StashScorer;
import dev.gamma.chunks.db.ChunkDatabase;
import dev.gamma.chunks.model.ChunkObservation;
import dev.gamma.chunks.model.ChunkQuery;
import dev.gamma.chunks.model.ChunkRecord;
import dev.gamma.chunks.model.ClassificationResult;
import dev.gamma.chunks.model.StashWeights;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.DoubleSetting;
import dev.gamma.config.setting.EnumSetting;
import dev.gamma.config.setting.IntSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.modules.misc.FakeCoordinates;
import dev.gamma.util.NotificationSound;
import dev.gamma.util.SoundNotifier;
import dev.gamma.util.ToastNotifier;
import dev.gamma.waypoints.Waypoint;
import dev.gamma.waypoints.WaypointCategory;
import dev.gamma.waypoints.WaypointSource;
import dev.gamma.waypoints.WaypointStore;
import net.minecraft.world.level.ChunkPos;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Roadmap: "Tunable weights, defaults documented" and "auto-create waypoints above a user-set
 * threshold." A {@link Module} (not a plain core service like {@link ChunkObservationCollector})
 * because it's genuinely optional and its weights are exactly the kind of per-field settings the
 * module contract already gives us for free.
 *
 * <p>Hooked from {@link ChunkObservationCollector#finalizeObservation} the same way {@code
 * NewChunks} is — a static {@link #instance}, since a core service has no DI path into a specific
 * module (see the design notes, Phase 4/5). On-demand scoring for {@code .chunks stashes}
 * lives in {@code GammaCommands} directly against {@link StashScorer}, since that path needs no
 * live module state beyond these same weight settings.
 */
public final class StashFinder extends Module {

	public static volatile StashFinder instance;

	private final DoubleSetting weightDensity = register(new DoubleSetting("WeightDensity", "How much raw storage-block count matters.", 0.5, 0, 1));
	private final DoubleSetting weightClustering = register(new DoubleSetting("WeightClustering", "How much rare block-entity variety (shulkers, ender chests) matters over common ones.", 0.3, 0, 1));
	private final DoubleSetting weightProximity = register(new DoubleSetting("WeightProximity", "How much being near other high-scoring chunks matters.", 0.2, 0, 1));
	private final IntSetting proximityRadius = register(new IntSetting("ProximityRadius", "Chunk radius considered \"nearby\" for the proximity signal.", 3, 1, 16));
	private final BoolSetting autoWaypoint = register(new BoolSetting("AutoWaypoint", "Automatically drop a Stash waypoint the first time a chunk's score crosses the threshold.", false));
	private final DoubleSetting autoWaypointThreshold = register(new DoubleSetting("AutoWaypointThreshold", "Minimum score (0-1) to auto-create a waypoint.", 0.75, 0, 1));
	private final IntSetting autoWaypointMinStorage = register(new IntSetting("AutoWaypointMinStorage", "Skip the (relatively expensive) neighbor-query scoring pass entirely below this raw storage-block count.", 3, 0, 64));
	private final BoolSetting sound = register(new BoolSetting("Sound", "Play a chime when a chunk crosses the threshold. Works with AutoWaypoint off — the scoring pass runs for either.", true));
	private final DoubleSetting soundVolume = register(new DoubleSetting("SoundVolume", "Chime volume. 1 is as loud as the game will play a UI sound; past that it is the vanilla Master/UI sliders doing the limiting, not this.", 1.0, 0.0, SoundNotifier.MAX_VOLUME));
	private final EnumSetting<NotificationSound> soundSample = register(new EnumSetting<>("SoundSample", "Which sample the chime uses.", NotificationSound.class, NotificationSound.AMETHYST));
	private final DoubleSetting soundPitch = register(new DoubleSetting("SoundPitch", "Playback pitch. Higher is brighter and more obviously a notification.", 1.0, 0.5, 2.0));
	private final BoolSetting toast = register(new BoolSetting("Toast", "Show a notification above the hotbar, with the coordinates and score, when a chunk crosses the threshold.", true));

	// Which containers count at all. Every type is always *logged* — these only decide what the
	// score looks at, so flipping one re-scores chunks already in the database rather than only
	// affecting chunks seen from now on. Defaults are the ones actually worth chasing; the rest are
	// off because worldgen hands them out (furnaces in villages, brewing stands in igloos).
	private final BoolSetting countChests = register(new BoolSetting("Chests", "Count chests. Bulk storage, and most false positives -- mineshafts and shipwrecks are made of these too.", true));
	private final BoolSetting countTrappedChests = register(new BoolSetting("TrappedChests", "Count trapped chests.", true));
	private final BoolSetting countBarrels = register(new BoolSetting("Barrels", "Count barrels.", true));
	private final BoolSetting countShulkerBoxes = register(new BoolSetting("ShulkerBoxes", "Count shulker boxes. Worldgen never places one, so every hit was carried there.", true));
	private final BoolSetting countEnderChests = register(new BoolSetting("EnderChests", "Count ender chests. Like shulkers, worldgen never places one -- off only because you asked for it, not because it is noisy.", false));
	private final BoolSetting countHoppers = register(new BoolSetting("Hoppers", "Count hoppers. Essentially never generated, so they lean strongly player-made.", true));
	private final BoolSetting countFurnaces = register(new BoolSetting("Furnaces", "Count furnaces. Common in villages and igloos.", false));
	private final BoolSetting countDroppers = register(new BoolSetting("Droppers", "Count droppers.", false));
	private final BoolSetting countDispensers = register(new BoolSetting("Dispensers", "Count dispensers. Generated in jungle temples.", false));
	private final BoolSetting countBrewingStands = register(new BoolSetting("BrewingStands", "Count brewing stands. Generated in igloo basements and end ships.", false));

	private final Set<Long> alreadyScored = ConcurrentHashMap.newKeySet();
	private final SoundNotifier chime = new SoundNotifier(500);
	private final ToastNotifier toasts = new ToastNotifier(2_000);

	public StashFinder() {
		super("StashFinder", "Scores logged chunks by storage density and clustering; can auto-waypoint the best hits.", Category.WORLD);
		instance = this;
	}

	/** Everything the scorer needs, in one object, so the live path and {@code .chunks stashes} cannot drift apart. */
	public StashWeights weights() {
		Set<String> counted = new HashSet<>();
		addIf(counted, countChests, "chest");
		addIf(counted, countTrappedChests, "trapped_chest");
		addIf(counted, countBarrels, "barrel");
		addIf(counted, countShulkerBoxes, "shulker_box");
		addIf(counted, countEnderChests, "ender_chest");
		addIf(counted, countHoppers, "hopper");
		addIf(counted, countFurnaces, "furnace");
		addIf(counted, countDroppers, "dropper");
		addIf(counted, countDispensers, "dispenser");
		addIf(counted, countBrewingStands, "brewing_stand");
		return new StashWeights(weightDensity.get(), weightClustering.get(), weightProximity.get(), proximityRadius.get(), counted);
	}

	private static void addIf(Set<String> target, BoolSetting setting, String type) {
		if (setting.get()) {
			target.add(type);
		}
	}

	/** Called by {@link ChunkObservationCollector} for every finalized observation — no-ops unless enabled and something wants the result, so logging cost stays near zero when this feature isn't in use. */
	public void onClassified(ChunkObservation observation, ClassificationResult result, ChunkDatabase database) {
		if (!isEnabled() || database == null || (!autoWaypoint.get() && !sound.get() && !toast.get())) {
			return;
		}
		// The cheap gate has to use the same filter as the score, or a chunk full of nothing but
		// excluded types still pays for the neighbour query before scoring zero.
		StashWeights weights = weights();
		if (weights.countedStorage(observation.blockEntityCounts()) < autoWaypointMinStorage.get()) {
			return;
		}
		long key = ChunkPos.pack(observation.chunkX(), observation.chunkZ());
		if (!alreadyScored.add(key)) {
			return;
		}
		int radius = weights.proximityRadius();
		ChunkQuery neighborQuery = new ChunkQuery(observation.dimension(),
				observation.chunkX() - radius, observation.chunkX() + radius,
				observation.chunkZ() - radius, observation.chunkZ() + radius,
				null, null, null, 512);
		database.query(neighborQuery, neighbors -> {
			ChunkRecord self = neighbors.stream()
					.filter(r -> r.x() == observation.chunkX() && r.z() == observation.chunkZ())
					.findFirst()
					.orElse(null);
			if (self == null) {
				return;
			}
			double score = StashScorer.scoreOne(self, neighbors, weights);
			if (score < autoWaypointThreshold.get()) {
				return;
			}
			double waypointX = observation.chunkX() * 16 + 8;
			double waypointZ = observation.chunkZ() * 16 + 8;
			if (sound.get()) {
				chime.play(soundSample.get().event(), soundPitch.get().floatValue(), soundVolume.get());
			}
			if (toast.get()) {
				// Block centre rather than chunk coordinates: this is a number you are going to walk
				// or fly to, and chunk coordinates would have to be multiplied by 16 first. Through
				// FakeCoordinates, so a disguised readout isn't undone by a find announcing where you
				// actually are two seconds later.
				toasts.show("Stash found", "%s — score %.0f%%".formatted(
						FakeCoordinates.describe(waypointX, 0, waypointZ, false), score * 100));
			}
			if (!autoWaypoint.get()) {
				return;
			}
			WaypointStore store = WaypointStore.instance;
			if (store == null) {
				return;
			}
			store.add(new Waypoint(WaypointStore.newId(), "Stash %.0f%%".formatted(score * 100), WaypointCategory.STASH,
					observation.dimension(), waypointX, 64, waypointZ, null, null, true, true,
					System.currentTimeMillis(), WaypointSource.STASH_AUTO));
		});
	}
}

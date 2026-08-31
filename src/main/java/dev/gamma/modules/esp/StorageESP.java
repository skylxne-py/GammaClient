package dev.gamma.modules.esp;

import dev.gamma.Gamma;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.ColorSetting;
import dev.gamma.config.setting.DoubleSetting;
import dev.gamma.config.setting.EnumSetting;
import dev.gamma.config.setting.IntSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.core.event.Subscription;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

/**
 * Storage block entities — chests, barrels, shulkers, hoppers, furnaces, droppers, dispensers,
 * brewing stands, ender chests. Core to base finding, so this reads straight off each loaded
 * chunk's own block-entity map ({@link LevelChunk#getBlockEntities()} — already-maintained,
 * O(1)-ish) rather than scanning block state — no caching layer needed, unlike {@link BlockESP}.
 */
public final class StorageESP extends Module {

	private final DoubleSetting maxDistance = register(new DoubleSetting("MaxDistance", "Max render distance, in blocks.", 48.0, 8.0, 1024.0));
	private final IntSetting maxHeight = register(new IntSetting("MaxHeight", "Only show containers at or below this Y, to cut surface noise when hunting underground. 320 = no limit.", Culling.HEIGHT_LIMIT_OFF, -64, Culling.HEIGHT_LIMIT_OFF));
	private final EnumSetting<EspMode> mode = register(new EnumSetting<>("Mode", "Box / tracer / both.", EspMode.class, EspMode.BOX));

	private final BoolSetting chestEnabled = register(new BoolSetting("Chests", "Show chests / trapped chests.", true));
	private final ColorSetting chestColor = register(new ColorSetting("ChestColor", "Chest / trapped chest color.", 0xFFFFAA00));
	private final BoolSetting enderChestEnabled = register(new BoolSetting("EnderChests", "Show ender chests.", true));
	private final ColorSetting enderChestColor = register(new ColorSetting("EnderChestColor", "Ender chest color.", 0xFF00E5CC));
	private final BoolSetting barrelEnabled = register(new BoolSetting("Barrels", "Show barrels.", true));
	private final ColorSetting barrelColor = register(new ColorSetting("BarrelColor", "Barrel color.", 0xFFAA7733));
	private final BoolSetting shulkerEnabled = register(new BoolSetting("ShulkerBoxes", "Show shulker boxes.", true));
	private final ColorSetting shulkerColor = register(new ColorSetting("ShulkerColor", "Shulker box color.", 0xFFCC55CC));
	private final BoolSetting hopperEnabled = register(new BoolSetting("Hoppers", "Show hoppers.", true));
	private final ColorSetting hopperColor = register(new ColorSetting("HopperColor", "Hopper color.", 0xFF888888));
	private final BoolSetting furnaceEnabled = register(new BoolSetting("Furnaces", "Show furnaces.", true));
	private final ColorSetting furnaceColor = register(new ColorSetting("FurnaceColor", "Furnace color.", 0xFFCC6633));
	private final BoolSetting dropperEnabled = register(new BoolSetting("DroppersAndDispensers", "Show droppers, dispensers, and brewing stands.", true));
	private final ColorSetting dropperColor = register(new ColorSetting("DropperColor", "Dropper / dispenser color.", 0xFF6699CC));

	private Subscription subscription;

	public StorageESP() {
		super("StorageESP", "Highlights storage block entities through walls.", Category.ESP);
	}

	@Override
	protected void onEnable() {
		subscription = listen(WorldRenderExtractEvent.class, this::onExtract);
	}

	@Override
	protected void onDisable() {
		Gamma.EVENT_BUS.unsubscribe(subscription);
	}

	private void onExtract(WorldRenderExtractEvent event) {
		ClientLevel level = event.context().level();
		Camera camera = event.context().camera();
		double max = maxDistance.get();
		int ceiling = maxHeight.get();
		// Clamped to the player's actual render distance -- nothing beyond that is ever loaded,
		// so scanning further is pure wasted work every single frame. MaxDistance's own cap is
		// now 1024 (raised from 256), which without this would mean up to a 65-chunk-radius grid
		// walk (17k+ hasChunk() calls) every frame regardless of what's actually around.
		int radius = Math.min((int) Math.ceil(max / 16.0) + 1, Minecraft.getInstance().options.renderDistance().get());
		ChunkPos center = ChunkPos.containing(camera.blockPosition());

		for (int cx = center.x() - radius; cx <= center.x() + radius; cx++) {
			for (int cz = center.z() - radius; cz <= center.z() + radius; cz++) {
				if (!level.hasChunk(cx, cz)) {
					continue;
				}
				LevelChunk chunk = level.getChunk(cx, cz);
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					Integer color = colorFor(blockEntity);
					if (color == null) {
						continue;
					}
					BlockPos pos = blockEntity.getBlockPos();
					if (!Culling.belowHeight(pos.getY(), ceiling)) {
						continue;
					}
					AABB box = ShapeBuilder.blockOutline(pos);
					if (!Culling.withinDistance(camera.position(), box.getCenter(), max)) {
						continue;
					}
					// Frustum-culled inside draw() for the box only -- a tracer's whole point is
					// showing something that isn't in view, so it always draws within distance regardless.
					boolean inFrustum = Culling.isVisible(camera.getCullFrustum(), box);
					EspShapeRenderer.draw(box, color, RenderPass.THROUGH_WALLS, mode.get(), camera, inFrustum);
				}
			}
		}
	}

	private Integer colorFor(BlockEntity blockEntity) {
		return switch (blockEntity) {
			case TrappedChestBlockEntity ignored -> chestEnabled.get() ? chestColor.get() : null;
			case ChestBlockEntity ignored -> chestEnabled.get() ? chestColor.get() : null;
			case EnderChestBlockEntity ignored -> enderChestEnabled.get() ? enderChestColor.get() : null;
			case BarrelBlockEntity ignored -> barrelEnabled.get() ? barrelColor.get() : null;
			case ShulkerBoxBlockEntity ignored -> shulkerEnabled.get() ? shulkerColor.get() : null;
			case HopperBlockEntity ignored -> hopperEnabled.get() ? hopperColor.get() : null;
			case FurnaceBlockEntity ignored -> furnaceEnabled.get() ? furnaceColor.get() : null;
			case DropperBlockEntity ignored -> dropperEnabled.get() ? dropperColor.get() : null;
			case DispenserBlockEntity ignored -> dropperEnabled.get() ? dropperColor.get() : null;
			case BrewingStandBlockEntity ignored -> dropperEnabled.get() ? dropperColor.get() : null;
			default -> null;
		};
	}
}

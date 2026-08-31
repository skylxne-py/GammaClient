package dev.gamma.modules.esp;

import dev.gamma.Gamma;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.ColorSetting;
import dev.gamma.config.setting.DoubleSetting;
import dev.gamma.config.setting.EntityTypeListSetting;
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
import dev.gamma.util.ColorUtil;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Per-entity-type ESP. Colored by {@link MobCategory} rather than a color-per-type map — the
 * {@code Setting<T>} hierarchy has no map-valued setting, and one color box per matched type
 * would need one — so entities are grouped the same way vanilla already groups them for spawning.
 * Players are excluded; {@link PlayerESP} owns those, with richer nametag info.
 */
public final class EntityESP extends Module {

	private final EntityTypeListSetting types = register(new EntityTypeListSetting(
			"Types", "Entity types to render.",
			List.of(EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.CREEPER, EntityTypes.SPIDER, EntityTypes.ENDERMAN)));
	private final EnumSetting<EspMode> mode = register(new EnumSetting<>("Mode", "Box / tracer / both.", EspMode.class, EspMode.BOX));
	private final ColorSetting hostileColor = register(new ColorSetting("HostileColor", "Color for MobCategory.MONSTER entities.", 0xFFFF3355));
	private final ColorSetting otherColor = register(new ColorSetting("OtherColor", "Color for every other category.", 0xFF55CCFF));
	private final EnumSetting<RenderPass> pass = register(new EnumSetting<>("Pass", "Depth-test behavior.", RenderPass.class, RenderPass.BOTH));
	private final DoubleSetting maxDistance = register(new DoubleSetting("MaxDistance", "Max render distance, in blocks.", 64.0, 4.0, 1024.0));
	private final IntSetting maxHeight = register(new IntSetting("MaxHeight", "Only show matches at or below this Y, to cut surface noise when hunting underground. 320 = no limit.", Culling.HEIGHT_LIMIT_OFF, -64, Culling.HEIGHT_LIMIT_OFF));
	private final BoolSetting distanceFade = register(new BoolSetting("DistanceFade", "Fade alpha with distance.", true));

	private Subscription subscription;

	public EntityESP() {
		super("EntityESP", "Highlights entities by type through walls.", Category.ESP);
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
		float partialTick = event.context().deltaTracker().getGameTimeDeltaPartialTick(false);
		double max = maxDistance.get();

		for (Entity entity : level.entitiesForRendering()) {
			if (entity instanceof Player || !types.contains(entity.getType())) {
				continue;
			}
			AABB box = ShapeBuilder.entityBox(entity, partialTick);
			if (!Culling.belowHeight(box.minY, maxHeight.get())) {
				continue;
			}
			if (!Culling.withinDistance(camera.position(), box.getCenter(), max)) {
				continue;
			}

			int color = entity.getType().getCategory() == MobCategory.MONSTER ? hostileColor.get() : otherColor.get();
			if (distanceFade.get()) {
				double distance = Math.sqrt(camera.position().distanceToSqr(box.getCenter()));
				color = ColorUtil.scaleAlpha(color, 1.0 - Math.min(1.0, distance / max) * 0.7);
			}

			// Frustum-culled inside draw() for the box only -- a tracer's whole point is showing
			// something that isn't in view, so it always draws within distance regardless.
			boolean inFrustum = Culling.isVisible(camera.getCullFrustum(), box);
			EspShapeRenderer.draw(box, color, pass.get(), mode.get(), camera, inFrustum);
		}
	}
}

package dev.gamma.modules.render;

import dev.gamma.Gamma;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.ColorSetting;
import dev.gamma.config.setting.DoubleSetting;
import dev.gamma.config.setting.EnumSetting;
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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * "Entity models through walls with a flat color" — a real, custom-shaded, chams-textured world
 * {@code RenderType} isn't reachable client-side (package-private construction, see the project conventions /
 * the design notes), so this is the same {@link dev.gamma.render.EspShapeRenderer} silhouette
 * EntityESP/PlayerESP use, just flat-colored and always through walls, which is what "chams"
 * reads as within that constraint.
 */
public final class Chams extends Module {

	private final ColorSetting color = register(new ColorSetting("Color", "Flat silhouette color.", 0xFFFF3399));
	private final EnumSetting<EspMode> mode = register(new EnumSetting<>("Mode", "Box / tracer / both.", EspMode.class, EspMode.BOX));
	private final BoolSetting includePlayers = register(new BoolSetting("IncludePlayers", "Apply to other players.", true));
	private final BoolSetting includeMobs = register(new BoolSetting("IncludeMobs", "Apply to non-player living entities.", true));
	private final DoubleSetting maxDistance = register(new DoubleSetting("MaxDistance", "Max render distance, in blocks.", 64.0, 4.0, 1024.0));

	private Subscription extractSubscription;

	public Chams() {
		super("Chams", "Renders entities through walls with a flat color.", Category.RENDER);
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
		float partialTick = event.context().deltaTracker().getGameTimeDeltaPartialTick(false);
		double max = maxDistance.get();
		int drawColor = color.get();

		for (Entity entity : level.entitiesForRendering()) {
			if (entity == camera.entity() || !(entity instanceof LivingEntity)) {
				continue;
			}
			boolean isPlayer = entity instanceof Player;
			if (isPlayer && !includePlayers.get() || !isPlayer && !includeMobs.get()) {
				continue;
			}
			AABB box = ShapeBuilder.entityBox(entity, partialTick);
			if (!Culling.withinDistance(camera.position(), box.getCenter(), max)) {
				continue;
			}
			// Frustum-culled inside draw() for the box only -- a tracer's whole point is showing
			// something that isn't in view, so it always draws within distance regardless.
			boolean inFrustum = Culling.isVisible(camera.getCullFrustum(), box);
			EspShapeRenderer.draw(box, drawColor, RenderPass.THROUGH_WALLS, mode.get(), camera, inFrustum);
		}
	}
}

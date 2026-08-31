package dev.gamma.modules.render;

import dev.gamma.Gamma;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.ColorSetting;
import dev.gamma.config.setting.DoubleSetting;
import dev.gamma.config.setting.EntityTypeListSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.core.event.Subscription;
import dev.gamma.core.event.events.WorldRenderExtractEvent;
import dev.gamma.render.Culling;
import dev.gamma.render.RenderPass;
import dev.gamma.render.Renderer3D;
import dev.gamma.render.ShapeBuilder;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.List;

/** A standalone tracer line to filtered entity types — distinct from EntityESP/PlayerESP's own optional tracers, for a tracers-only loadout. */
public final class Tracers extends Module {

	private final EntityTypeListSetting types = register(new EntityTypeListSetting("Types", "Entity types to draw a tracer to.", List.of(EntityTypes.PLAYER)));
	private final ColorSetting color = register(new ColorSetting("Color", "Tracer color.", 0xFFFF5555));
	private final DoubleSetting maxDistance = register(new DoubleSetting("MaxDistance", "Max range, in blocks.", 64.0, 4.0, 1024.0));
	private final BoolSetting excludeSelf = register(new BoolSetting("ExcludeSelf", "Never draw a tracer to yourself.", true));

	private Subscription extractSubscription;

	public Tracers() {
		super("Tracers", "Standalone tracer lines to filtered entity types.", Category.RENDER);
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

		for (Entity entity : level.entitiesForRendering()) {
			if (entity == camera.entity() && (excludeSelf.get() || entity instanceof Player)) {
				continue;
			}
			if (!types.contains(entity.getType())) {
				continue;
			}
			AABB box = ShapeBuilder.entityBox(entity, partialTick);
			// Distance only, no frustum check -- this module draws nothing but tracers, and a
			// tracer's whole point is showing something that isn't in view.
			if (!Culling.withinDistance(camera.position(), box.getCenter(), max)) {
				continue;
			}
			Renderer3D.tracer(ShapeBuilder.tracerOrigin(camera), box.getCenter(), color.get(), RenderPass.THROUGH_WALLS);
		}
	}
}

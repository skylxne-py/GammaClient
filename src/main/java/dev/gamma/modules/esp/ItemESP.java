package dev.gamma.modules.esp;

import dev.gamma.Gamma;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.ColorSetting;
import dev.gamma.config.setting.DoubleSetting;
import dev.gamma.config.setting.EnumSetting;
import dev.gamma.config.setting.IntSetting;
import dev.gamma.config.setting.StringSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.core.event.Subscription;
import dev.gamma.core.event.events.WorldRenderExtractEvent;
import dev.gamma.render.Culling;
import dev.gamma.render.EspMode;
import dev.gamma.render.EspShapeRenderer;
import dev.gamma.render.RenderPass;
import dev.gamma.render.Renderer3D;
import dev.gamma.render.ShapeBuilder;
import dev.gamma.util.ColorUtil;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/** Dropped items — stack count and an item-name substring filter, since there's no generic name-search setting type. */
public final class ItemESP extends Module {

	private final ColorSetting color = register(new ColorSetting("Color", "Box color.", 0xFFFFD700));
	private final EnumSetting<EspMode> mode = register(new EnumSetting<>("Mode", "Box / tracer / both.", EspMode.class, EspMode.BOX));
	private final EnumSetting<RenderPass> pass = register(new EnumSetting<>("Pass", "Depth-test behavior.", RenderPass.class, RenderPass.THROUGH_WALLS));
	private final DoubleSetting maxDistance = register(new DoubleSetting("MaxDistance", "Max render distance, in blocks.", 48.0, 4.0, 1024.0));
	private final IntSetting maxHeight = register(new IntSetting("MaxHeight", "Only show matches at or below this Y, to cut surface noise when hunting underground. 320 = no limit.", Culling.HEIGHT_LIMIT_OFF, -64, Culling.HEIGHT_LIMIT_OFF));
	private final BoolSetting showCount = register(new BoolSetting("ShowCount", "Draw the stack count above each item.", true));
	private final StringSetting nameFilter = register(new StringSetting("NameFilter", "Only show items whose name contains this text (case-insensitive, blank = all).", ""));

	private Subscription subscription;

	public ItemESP() {
		super("ItemESP", "Highlights dropped item entities through walls.", Category.ESP);
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
		String filter = nameFilter.get().toLowerCase(Locale.ROOT);
		int base = color.get();

		for (Entity entity : level.entitiesForRendering()) {
			if (!(entity instanceof ItemEntity itemEntity)) {
				continue;
			}
			ItemStack stack = itemEntity.getItem();
			if (!filter.isBlank() && !stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(filter)) {
				continue;
			}
			AABB box = ShapeBuilder.entityBox(itemEntity, partialTick);
			if (!Culling.belowHeight(box.minY, maxHeight.get())) {
				continue;
			}
			if (!Culling.withinDistance(camera.position(), box.getCenter(), max)) {
				continue;
			}
			// Frustum-culled for the box/count label only -- a tracer's whole point is showing
			// something that isn't in view, so it always draws within distance regardless.
			boolean inFrustum = Culling.isVisible(camera.getCullFrustum(), box);

			double distance = Math.sqrt(camera.position().distanceToSqr(box.getCenter()));
			int drawColor = ColorUtil.scaleAlpha(base, 1.0 - Math.min(1.0, distance / max) * 0.6);
			EspShapeRenderer.draw(box, drawColor, pass.get(), mode.get(), camera, inFrustum);

			if (inFrustum && showCount.get() && stack.getCount() > 1) {
				Vec3 above = box.getCenter().add(0, box.getYsize() / 2.0 + 0.2, 0);
				Renderer3D.text3d(above, "x" + stack.getCount(), drawColor, pass.get());
			}
		}
	}
}

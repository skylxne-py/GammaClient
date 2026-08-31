package dev.gamma.modules.basehunting;

import dev.gamma.Gamma;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.ColorSetting;
import dev.gamma.config.setting.DoubleSetting;
import dev.gamma.config.setting.EnumSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.core.event.Subscription;
import dev.gamma.core.event.events.TickEvent;
import dev.gamma.core.event.events.WorldRenderExtractEvent;
import dev.gamma.render.Culling;
import dev.gamma.render.EspMode;
import dev.gamma.render.EspShapeRenderer;
import dev.gamma.render.RenderPass;
import dev.gamma.render.Renderer3D;
import dev.gamma.render.ShapeBuilder;
import dev.gamma.util.ColorUtil;
import dev.gamma.util.ToastNotifier;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Finds elytras displayed in item frames.
 *
 * <p>The item is not inferred from anything — {@code ItemFrame.getItem()} reads a synced
 * {@code EntityDataAccessor<ItemStack>} that the server pushes in the frame's metadata packet,
 * because the client needs it to draw the frame at all. This module reads the same field the
 * renderer does.
 *
 * <h2>Why an elytra in a frame is a base signal</h2>
 *
 * <p>An elytra cannot generate in a frame. Someone flew to an End city, came back, and mounted it on
 * a wall — which means a base, and one belonging to somebody far enough into the game to bother.
 *
 * <h2>The range limit is real and worth knowing</h2>
 *
 * <p>Frames are <em>entities</em>, not block entities, so they only exist client-side inside the
 * server's entity tracking range — typically far shorter than chunk view distance, and shorter
 * again on a busy server. This finds what you fly past. It cannot sweep a base from render distance
 * the way {@code BlockESP} does, and no client-side change can lift that: the data simply is not
 * sent.
 *
 * <p>Detection and announcing run on tick; drawing runs in the extract event. Frames do not move,
 * but the split is what keeps the toast firing once per elytra found rather than once per rendered
 * frame of animation.
 */
public final class ElytraFinder extends Module {

	/**
	 * Frames are flat against a wall, so one axis of the bounding box is nearly zero and an
	 * un-inflated outline reads as a line rather than a box.
	 */
	private static final double BOX_INFLATE = 0.06;

	/** Matches the other finders; see {@link ToastNotifier} for why the valve is per-module. */
	private static final long TOAST_INTERVAL_MILLIS = 2_000;

	private static final int NAME_ALPHA_FLOOR = 60;

	private final ColorSetting color = register(new ColorSetting("Color", "Highlight color.", 0xFF00E5A0));
	private final EnumSetting<EspMode> mode = register(new EnumSetting<>("Mode", "Box / tracer / both.", EspMode.class, EspMode.BOTH));
	private final EnumSetting<RenderPass> pass = register(new EnumSetting<>("Pass", "Depth-test behavior.", RenderPass.class, RenderPass.THROUGH_WALLS));
	private final DoubleSetting maxDistance = register(new DoubleSetting("MaxDistance",
			"Max render distance, in blocks. Capped in practice by the server's entity tracking range, which is usually shorter.", 128.0, 8.0, 1024.0));
	private final BoolSetting showName = register(new BoolSetting("ShowName", "Draw the elytra's name above the frame — worth leaving on, since a renamed one tells you whose base it is.", true));
	private final BoolSetting toast = register(new BoolSetting("Toast", "Announce an elytra the first time it comes into range.", true));

	private final ToastNotifier toasts = new ToastNotifier(TOAST_INTERVAL_MILLIS);

	/**
	 * Frames already announced. Keyed by entity UUID rather than network id: leaving and re-entering
	 * tracking range recycles network ids, and being told about the same frame every time you turn
	 * around is the thing that makes a finder unusable.
	 */
	private final Set<UUID> announced = new HashSet<>();

	private Subscription tickSubscription;
	private Subscription extractSubscription;

	public ElytraFinder() {
		super("ElytraFinder", "Highlights item frames with an elytra in them.", Category.BASE_HUNTING);
	}

	@Override
	protected void onEnable() {
		announced.clear();
		toasts.reset();
		tickSubscription = listen(TickEvent.class, this::onTick);
		extractSubscription = listen(WorldRenderExtractEvent.class, this::onExtract);
	}

	@Override
	protected void onDisable() {
		Gamma.EVENT_BUS.unsubscribe(tickSubscription);
		Gamma.EVENT_BUS.unsubscribe(extractSubscription);
		announced.clear();
	}

	private void onTick(TickEvent event) {
		if (event.phase() != TickEvent.Phase.END || !toast.get()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = client.level;
		if (level == null || client.player == null) {
			return;
		}
		double max = maxDistance.get();
		Vec3 eye = client.player.getEyePosition();

		for (Entity entity : level.entitiesForRendering()) {
			if (!(entity instanceof ItemFrame frame)) {
				continue;
			}
			ItemStack stack = frame.getItem();
			if (!isElytra(stack) || !Culling.withinDistance(eye, entity.position(), max)) {
				continue;
			}
			if (announced.add(entity.getUUID())) {
				toasts.show("Elytra", stack.getHoverName().getString()
						+ " at " + entity.blockPosition().toShortString());
			}
		}
	}

	/** Covers glow frames too — {@code GlowItemFrame} extends {@code ItemFrame} and carries the same item. */
	private boolean isElytra(ItemStack stack) {
		return !stack.isEmpty() && stack.is(Items.ELYTRA);
	}

	private void onExtract(WorldRenderExtractEvent event) {
		ClientLevel level = event.context().level();
		Camera camera = event.context().camera();
		float partialTick = event.context().deltaTracker().getGameTimeDeltaPartialTick(false);
		double max = maxDistance.get();
		int base = color.get();

		for (Entity entity : level.entitiesForRendering()) {
			if (!(entity instanceof ItemFrame frame)) {
				continue;
			}
			ItemStack stack = frame.getItem();
			if (!isElytra(stack)) {
				continue;
			}
			AABB box = ShapeBuilder.entityBox(frame, partialTick).inflate(BOX_INFLATE);
			if (!Culling.withinDistance(camera.position(), box.getCenter(), max)) {
				continue;
			}
			// Gates the box and the label only. A tracer exists to point at something off screen,
			// so it draws whenever the frame is in range.
			boolean inFrustum = Culling.isVisible(camera.getCullFrustum(), box);

			double distance = Math.sqrt(camera.position().distanceToSqr(box.getCenter()));
			int drawColor = ColorUtil.scaleAlpha(base, 1.0 - Math.min(1.0, distance / max) * 0.6);
			EspShapeRenderer.draw(box, drawColor, pass.get(), mode.get(), camera, inFrustum);

			if (inFrustum && showName.get()) {
				Vec3 above = box.getCenter().add(0, box.getYsize() / 2.0 + 0.25, 0);
				Renderer3D.text3d(above, stack.getHoverName().getString(),
						ColorUtil.withAlpha(drawColor, Math.max(NAME_ALPHA_FLOOR, (drawColor >>> 24) & 0xFF)), pass.get());
			}
		}
	}
}

package dev.gamma.modules.world;

import dev.gamma.Gamma;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.DoubleSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.core.event.Subscription;
import dev.gamma.core.event.events.WorldRenderExtractEvent;
import dev.gamma.render.RenderPass;
import dev.gamma.render.Renderer2D;
import dev.gamma.render.Renderer3D;
import dev.gamma.waypoints.DimensionConversion;
import dev.gamma.waypoints.Waypoint;
import dev.gamma.waypoints.WaypointIconRenderer;
import dev.gamma.waypoints.WaypointStore;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * The rendering half of Phase 6's waypoint system — {@link WaypointStore} owns the data, this
 * draws it: a vertical beacon beam, a floating name+distance label (both real 3D, drawn from
 * {@link WorldRenderExtractEvent} like every other Renderer3D user), and an on-screen edge
 * indicator for waypoints currently off-screen. The edge indicator is registered as its own
 * always-on {@link HudElement} layer (mirrors {@code HudManager}'s single-layer-checks-each-
 * component's-own-enabled-flag pattern) so a toggleable {@link Module} can still contribute a 2D
 * overlay without a HUD-element-per-module registration story.
 *
 * <p>The edge indicator's on-screen position is a horizontal-bearing-only heuristic (player yaw
 * vs. bearing to the waypoint, clamped to the horizontal FOV), not a true world-to-screen
 * projection through the camera's projection matrix — an accepted simplification in the same
 * spirit as {@code Renderer2D}'s rounded-corner AA edge case (see the design notes):
 * good enough for "which way do I turn", not pixel-accurate for where the waypoint would actually
 * appear if it were on-screen.
 */
public final class WaypointsModule extends Module implements HudElement {

	private static final Identifier LAYER_ID = Identifier.fromNamespaceAndPath(Gamma.MOD_ID, "waypoint_edges");

	private final BoolSetting beaconBeam = register(new BoolSetting("BeaconBeam", "Draw a vertical beam at each waypoint.", true));
	private final BoolSetting labels = register(new BoolSetting("Labels", "Draw a floating name + distance label at each waypoint.", true));
	private final BoolSetting edgeIndicators = register(new BoolSetting("EdgeIndicators", "Show an on-screen arrow toward off-screen waypoints.", true));
	private final BoolSetting crossDimension = register(new BoolSetting("CrossDimension", "Also show a waypoint's Overworld/Nether equivalent position when you're in the other one.", true));
	private final DoubleSetting maxDistance = register(new DoubleSetting("MaxDistance", "Stop drawing beams, labels and edge indicators beyond this many blocks (0 = unlimited).", 256, 0, 4096));
	private final DoubleSetting minLabelScale = register(new DoubleSetting("MinLabelScale", "Smallest label scale at max distance, so far-away labels stay legible instead of shrinking to nothing.", 0.5, 0.1, 1.0));

	private Subscription extractSubscription;

	public WaypointsModule() {
		super("Waypoints", "Renders stored waypoints: beacon beams, labels, and off-screen edge indicators.", Category.WORLD);
		HudElementRegistry.addLast(LAYER_ID, this);
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
		WaypointStore store = WaypointStore.instance;
		if (store == null) {
			return;
		}
		ClientLevel level = event.context().level();
		Camera camera = event.context().camera();
		String dimension = level.dimension().identifier().toString();
		Vec3 cam = camera.position();
		double maxDist = maxDistance.get();

		for (Waypoint w : store.all()) {
			if (!w.visible()) {
				continue;
			}
			if (w.dimension().equals(dimension)) {
				draw(w, w.x(), w.y(), w.z(), cam, maxDist, level);
			} else if (crossDimension.get()) {
				DimensionConversion.convert(w.dimension(), w.x(), w.z(), dimension)
						.ifPresent(p -> draw(w, p.x(), w.y(), p.z(), cam, maxDist, level));
			}
		}
	}

	private void draw(Waypoint w, double x, double y, double z, Vec3 cam, double maxDist, ClientLevel level) {
		double distance = cam.distanceTo(new Vec3(x, y, z));
		if (maxDist > 0 && distance > maxDist) {
			return;
		}
		int color = w.color();
		if (beaconBeam.get() && w.beaconBeam()) {
			Renderer3D.line(new Vec3(x, level.getMinY(), z), new Vec3(x, level.getMaxY(), z), color, RenderPass.THROUGH_WALLS);
		}
		if (labels.get()) {
			float scale = maxDist > 0
					? (float) Math.max(minLabelScale.get(), 1.0 - distance / maxDist)
					: 1.0f;
			String text = w.name() + " (" + Math.round(distance) + "m)";
			Renderer3D.text3d(new Vec3(x, y + 1.2, z), text, color, scale, RenderPass.THROUGH_WALLS);
		}
	}

	// -- off-screen edge indicators (2D, always-registered layer) ------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor extractor, DeltaTracker deltaTracker) {
		if (!isEnabled() || !edgeIndicators.get()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		WaypointStore store = WaypointStore.instance;
		if (store == null || client.player == null || client.level == null) {
			return;
		}
		String dimension = client.level.dimension().identifier().toString();
		int screenWidth = extractor.guiWidth();
		int screenHeight = extractor.guiHeight();
		double fovDegrees = client.options.fov().get();
		double yaw = client.player.getYRot();
		double px = client.player.getX();
		double pz = client.player.getZ();
		double maxDist = maxDistance.get();

		Renderer2D renderer = new Renderer2D(extractor);
		for (Waypoint w : store.all()) {
			if (!w.visible()) {
				continue;
			}
			double wx;
			double wz;
			if (w.dimension().equals(dimension)) {
				wx = w.x();
				wz = w.z();
			} else if (crossDimension.get()) {
				var converted = DimensionConversion.convert(w.dimension(), w.x(), w.z(), dimension);
				if (converted.isEmpty()) {
					continue;
				}
				wx = converted.get().x();
				wz = converted.get().z();
			} else {
				continue;
			}
			double dx = wx - px;
			double dz = wz - pz;
			double horizontalSq = dx * dx + dz * dz;
			if (horizontalSq < 4.0) {
				continue; // standing on it
			}
			// The same MaxDistance gate the in-world beam and label already honour. Without it the
			// edge indicator -- an icon *and* the waypoint's name -- kept drawing for every waypoint
			// in the world at any range, so the beam vanished at 256 blocks while its label stayed
			// stacked up across the middle of the screen. Horizontal-only, to match the bearing
			// heuristic this indicator is built on.
			if (maxDist > 0 && horizontalSq > maxDist * maxDist) {
				continue;
			}
			double bearing = Math.toDegrees(Math.atan2(-dx, dz));
			double relative = normalizeDegrees(bearing - yaw);
			if (Math.abs(relative) < fovDegrees / 2.0) {
				continue; // roughly on-screen already — the in-world label/beam covers it
			}
			drawEdgeIndicator(renderer, client.font, screenWidth, screenHeight, relative, w);
		}
	}

	private void drawEdgeIndicator(Renderer2D renderer, Font font, int screenWidth, int screenHeight, double relativeDegrees, Waypoint w) {
		double clamped = Math.max(-90, Math.min(90, relativeDegrees));
		double t = clamped / 90.0;
		int margin = 16;
		int x = (int) Math.round(screenWidth / 2.0 + t * (screenWidth / 2.0 - margin));
		int y = screenHeight / 2 - 40;
		int color = w.color();
		WaypointIconRenderer.draw(renderer, w.icon(), x - 8, y - 8, 2, color);
		String label = w.name();
		renderer.text(font, label, x - font.width(label) / 2, y + 10, color);
	}

	private static double normalizeDegrees(double degrees) {
		double d = degrees % 360.0;
		if (d < -180.0) {
			d += 360.0;
		} else if (d > 180.0) {
			d -= 360.0;
		}
		return d;
	}
}

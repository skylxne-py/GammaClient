package dev.gamma.gui.map;

import dev.gamma.gui.GammaScreen;
import dev.gamma.render.Renderer2D;
import dev.gamma.waypoints.Waypoint;
import dev.gamma.waypoints.WaypointCategory;
import dev.gamma.waypoints.WaypointIconRenderer;
import dev.gamma.waypoints.WaypointSource;
import dev.gamma.waypoints.WaypointStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;

import static com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE;
import static com.mojang.blaze3d.platform.InputConstants.KEY_H;

/**
 * The fullscreen chunk map overlay. Roadmap: grid of logged chunks colored by classification and
 * shaded by confidence, a heatmap mode, pan/zoom, coordinate readout under cursor, click to create
 * a waypoint, player position/facing, and other players' last-known positions. "Last known" here
 * just means "wherever they currently are while in render distance" — this screen has no separate
 * position history of its own to draw from (that's what {@code LogoutSpots} is for).
 */
public final class MapOverlayScreen extends GammaScreen {

	private static final double MIN_BLOCKS_PER_PIXEL = 0.25;
	private static final double MAX_BLOCKS_PER_PIXEL = 64.0;
	private static final double DRAG_CLICK_THRESHOLD = 4.0;

	private final MapTileCache tileCache = new MapTileCache();
	private double centerX;
	private double centerZ;
	private double blocksPerPixel = 2.0;
	private boolean heatmap;
	private double dragAccumulated;
	private int waypointCounter;

	public MapOverlayScreen() {
		super(Component.literal("Gamma Map"));
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			centerX = client.player.getX();
			centerZ = client.player.getZ();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		Renderer2D renderer = new Renderer2D(extractor);
		renderer.setTextShadow(false);
		Minecraft client = Minecraft.getInstance();
		String dimension = client.level != null ? client.level.dimension().identifier().toString() : "minecraft:overworld";

		MapRenderer.Viewport viewport = new MapRenderer.Viewport(0, 0, width, height, centerX, centerZ, blocksPerPixel);
		MapRenderer.render(renderer, viewport, tileCache, dimension, heatmap);

		if (client.level != null) {
			for (AbstractClientPlayer player : client.level.players()) {
				int color = player == client.player ? 0xFF66CCFF : 0xFFFFFFFF;
				if (player == client.player) {
					MapRenderer.drawPlayerMarker(renderer, viewport, player.getX(), player.getZ(), player.getYRot(), color);
				} else {
					MapRenderer.drawDot(renderer, viewport, player.getX(), player.getZ(), color);
					renderer.text(font, player.getGameProfile().name(), viewport.screenXFor(player.getX()) + 4, viewport.screenZFor(player.getZ()) - 4, color);
				}
			}
		}

		WaypointStore store = WaypointStore.instance;
		if (store != null) {
			for (Waypoint waypoint : store.forDimension(dimension)) {
				if (!waypoint.visible()) {
					continue;
				}
				int sx = viewport.screenXFor(waypoint.x());
				int sz = viewport.screenZFor(waypoint.z());
				WaypointIconRenderer.draw(renderer, waypoint.icon(), sx - 4, sz - 4, 1, waypoint.color());
				renderer.text(font, waypoint.name(), sx + 6, sz - 4, waypoint.color());
			}
		}

		renderTopBar(renderer, viewport, mouseX, mouseY);
	}

	private void renderTopBar(Renderer2D renderer, MapRenderer.Viewport viewport, int mouseX, int mouseY) {
		double worldX = viewport.worldXFor(mouseX);
		double worldZ = viewport.worldZFor(mouseY);
		String info = "%.0f, %.0f  |  %s  |  [scroll] zoom  [drag] pan  [click] waypoint  [H] heatmap%s"
				.formatted(worldX, worldZ, dimensionLabel(), heatmap ? " (on)" : "");
		renderer.fill(0, 0, width, 16, 0xAA000000);
		renderer.text(font, info, 6, 4, 0xFFFFFFFF);
	}

	private String dimensionLabel() {
		Minecraft client = Minecraft.getInstance();
		return client.level != null ? client.level.dimension().identifier().toString() : "unknown";
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (event.button() != 0) {
			return false;
		}
		centerX -= dragX * blocksPerPixel;
		centerZ -= dragY * blocksPerPixel;
		dragAccumulated += Math.abs(dragX) + Math.abs(dragY);
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		dragAccumulated = 0;
		return event.button() == 0;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (event.button() != 0 || dragAccumulated >= DRAG_CLICK_THRESHOLD) {
			return false;
		}
		Minecraft client = Minecraft.getInstance();
		String dimension = client.level != null ? client.level.dimension().identifier().toString() : "minecraft:overworld";
		MapRenderer.Viewport viewport = new MapRenderer.Viewport(0, 0, width, height, centerX, centerZ, blocksPerPixel);
		double worldX = viewport.worldXFor(event.x());
		double worldZ = viewport.worldZFor(event.y());
		WaypointStore store = WaypointStore.instance;
		if (store != null) {
			waypointCounter++;
			store.add(new Waypoint(WaypointStore.newId(), "WP " + waypointCounter, WaypointCategory.MISC, dimension,
					worldX, 64, worldZ, null, null, true, true, System.currentTimeMillis(), WaypointSource.MAP));
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		double factor = scrollY > 0 ? 0.85 : 1.0 / 0.85;
		blocksPerPixel = Math.max(MIN_BLOCKS_PER_PIXEL, Math.min(MAX_BLOCKS_PER_PIXEL, blocksPerPixel * factor));
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == KEY_ESCAPE) {
			Minecraft.getInstance().gui.setScreen(null);
			return true;
		}
		if (event.key() == KEY_H) {
			heatmap = !heatmap;
			return true;
		}
		return false;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}
}

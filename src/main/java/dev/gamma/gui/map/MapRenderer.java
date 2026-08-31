package dev.gamma.gui.map;

import dev.gamma.chunks.model.ChunkRecord;
import dev.gamma.render.Renderer2D;
import dev.gamma.util.ColorUtil;

/** Shared viewport math and draw routines used by both {@link MapOverlayScreen} (fullscreen, interactive) and {@code gui.hud.elements.MapMinimapElement} (fixed corner, read-only). */
public final class MapRenderer {

	/** Fixed heatmap scale (raw storage-block count at full-intensity red) rather than a dynamic per-frame max, so the color scale doesn't shift as you pan/zoom — a stash-dense chunk always reads the same color. */
	private static final int HEATMAP_CAP = 12;

	private MapRenderer() {
	}

	/** {@code blocksPerPixel} is the zoom level — smaller is more zoomed in. */
	public record Viewport(int screenX, int screenY, int screenWidth, int screenHeight, double centerX, double centerZ, double blocksPerPixel) {

		public int screenXFor(double worldX) {
			return screenX + screenWidth / 2 + (int) Math.round((worldX - centerX) / blocksPerPixel);
		}

		public int screenZFor(double worldZ) {
			return screenY + screenHeight / 2 + (int) Math.round((worldZ - centerZ) / blocksPerPixel);
		}

		public double worldXFor(double screenPixelX) {
			return centerX + (screenPixelX - screenX - screenWidth / 2.0) * blocksPerPixel;
		}

		public double worldZFor(double screenPixelY) {
			return centerZ + (screenPixelY - screenY - screenHeight / 2.0) * blocksPerPixel;
		}
	}

	/** {@code [minChunkX, maxChunkX, minChunkZ, maxChunkZ]} currently visible, padded by one chunk. */
	public static int[] visibleChunkRange(Viewport vp) {
		double minWorldX = vp.worldXFor(vp.screenX());
		double maxWorldX = vp.worldXFor(vp.screenX() + vp.screenWidth());
		double minWorldZ = vp.worldZFor(vp.screenY());
		double maxWorldZ = vp.worldZFor(vp.screenY() + vp.screenHeight());
		int minChunkX = (int) Math.floor(Math.min(minWorldX, maxWorldX) / 16) - 1;
		int maxChunkX = (int) Math.floor(Math.max(minWorldX, maxWorldX) / 16) + 1;
		int minChunkZ = (int) Math.floor(Math.min(minWorldZ, maxWorldZ) / 16) - 1;
		int maxChunkZ = (int) Math.floor(Math.max(minWorldZ, maxWorldZ) / 16) + 1;
		return new int[] {minChunkX, maxChunkX, minChunkZ, maxChunkZ};
	}

	public static void render(Renderer2D renderer, Viewport vp, MapTileCache cache, String dimension, boolean heatmap) {
		int x0 = vp.screenX();
		int y0 = vp.screenY();
		int x1 = x0 + vp.screenWidth();
		int y1 = y0 + vp.screenHeight();
		renderer.pushScissor(x0, y0, x1, y1);
		renderer.fill(x0, y0, x1, y1, 0xFF15161A);

		int[] range = visibleChunkRange(vp);
		cache.ensureCovers(dimension, range[0], range[1], range[2], range[3]);

		int cellSize = Math.max(1, (int) Math.round(16.0 / vp.blocksPerPixel()));
		for (int cx = range[0]; cx <= range[1]; cx++) {
			for (int cz = range[2]; cz <= range[3]; cz++) {
				ChunkRecord record = cache.get(dimension, cx, cz);
				if (record == null) {
					continue;
				}
				int color = heatmap ? heatColor(record.storageCount()) : classificationColor(record);
				int sx = vp.screenXFor(cx * 16);
				int sz = vp.screenZFor(cz * 16);
				renderer.fill(sx, sz, sx + cellSize, sz + cellSize, color);
			}
		}
		renderer.popScissor();
	}

	private static int classificationColor(ChunkRecord record) {
		int base = switch (record.classification()) {
			case LIKELY_NEW -> 0xFF33DD77;
			case LIKELY_EXISTING -> 0xFF555555;
			case UNKNOWN -> 0xFFCCCC33;
		};
		double shade = switch (record.classification()) {
			case LIKELY_NEW -> record.confidence();
			case LIKELY_EXISTING -> 1.0 - record.confidence();
			case UNKNOWN -> 0.5;
		};
		return ColorUtil.withAlpha(base, (int) Math.round(110 + 145 * Math.max(0.1, shade)));
	}

	private static int heatColor(int storageCount) {
		double t = Math.min(1.0, storageCount / (double) HEATMAP_CAP);
		double hue = 220.0 - t * 220.0;
		return ColorUtil.fromHsv(hue, 0.85, 0.35 + 0.55 * t, 230);
	}

	public static void drawPlayerMarker(Renderer2D renderer, Viewport vp, double worldX, double worldZ, float yawDegrees, int color) {
		int sx = vp.screenXFor(worldX);
		int sz = vp.screenZFor(worldZ);
		renderer.roundedRect(sx - 3, sz - 3, 6, 6, 3, color);
		double rad = Math.toRadians(yawDegrees);
		double dx = -Math.sin(rad);
		double dz = Math.cos(rad);
		renderer.line(sx, sz, sx + dx * 10, sz + dz * 10, 2f, color);
	}

	public static void drawDot(Renderer2D renderer, Viewport vp, double worldX, double worldZ, int color) {
		int sx = vp.screenXFor(worldX);
		int sz = vp.screenZFor(worldZ);
		renderer.roundedRect(sx - 2, sz - 2, 4, 4, 2, color);
	}
}

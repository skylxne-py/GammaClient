package dev.gamma.waypoints;

import dev.gamma.render.Renderer2D;

/** Draws a {@link WaypointIcon}'s pixel mask scaled to {@code pixelSize} per cell — shared by the map overlay, minimap, and edge indicators. */
public final class WaypointIconRenderer {

	private WaypointIconRenderer() {
	}

	/** {@code x}/{@code y} is the top-left of the icon's bounding box. */
	public static void draw(Renderer2D renderer, WaypointIcon icon, int x, int y, int pixelSize, int color) {
		for (int row = 0; row < WaypointIcon.SIZE; row++) {
			for (int col = 0; col < WaypointIcon.SIZE; col++) {
				if (!icon.filled(row, col)) {
					continue;
				}
				int px = x + col * pixelSize;
				int py = y + row * pixelSize;
				renderer.fill(px, py, px + pixelSize, py + pixelSize, color);
			}
		}
	}

	public static int size(int pixelSize) {
		return WaypointIcon.SIZE * pixelSize;
	}
}

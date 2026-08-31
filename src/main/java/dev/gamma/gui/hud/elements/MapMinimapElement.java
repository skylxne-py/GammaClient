package dev.gamma.gui.hud.elements;

import dev.gamma.gui.hud.Anchor;
import dev.gamma.gui.hud.HudComponent;
import dev.gamma.gui.hud.HudContext;
import dev.gamma.gui.map.MapRenderer;
import dev.gamma.gui.map.MapTileCache;
import dev.gamma.render.Renderer2D;
import net.minecraft.client.gui.Font;

/**
 * The corner-minimap presentation of the roadmap's "toggleable fullscreen and minimap corner"
 * chunk overlay (the fullscreen form is {@code gui.map.MapOverlayScreen}, opened via {@code
 * .gamma map}). Reuses the existing {@link HudComponent} framework wholesale — drag/anchor/scale
 * all come for free — rather than inventing a second draggable-overlay system; unlike the
 * fullscreen screen it's read-only (no pan/zoom/click), always centered on the player.
 */
public final class MapMinimapElement extends HudComponent {

	private static final int SIZE = 96;
	private static final double BLOCKS_PER_PIXEL = 3.0;

	private final MapTileCache tileCache = new MapTileCache();
	private boolean heatmap;

	public MapMinimapElement() {
		super("map_minimap", "Map (minimap)", Anchor.TOP_RIGHT, 6, 6);
		setEnabled(false);
	}

	public void setHeatmap(boolean heatmap) {
		this.heatmap = heatmap;
	}

	@Override
	public int measureWidth(Font font, HudContext ctx) {
		return SIZE;
	}

	@Override
	public int measureHeight(Font font, HudContext ctx) {
		return SIZE;
	}

	@Override
	public void render(Renderer2D renderer, Font font, int x, int y, HudContext ctx) {
		renderer.roundedRect(x - 2, y - 2, SIZE + 4, SIZE + 4, 8, 0xCC0A0A0C);
		if (!ctx.hasPlayer() || ctx.level() == null) {
			renderer.roundedRectOutline(x - 2, y - 2, SIZE + 4, SIZE + 4, 8, 1.5f, 0xFF666666);
			return;
		}
		String dimension = ctx.level().dimension().identifier().toString();
		MapRenderer.Viewport viewport = new MapRenderer.Viewport(x, y, SIZE, SIZE, ctx.player().getX(), ctx.player().getZ(), BLOCKS_PER_PIXEL);
		MapRenderer.render(renderer, viewport, tileCache, dimension, heatmap);
		MapRenderer.drawPlayerMarker(renderer, viewport, ctx.player().getX(), ctx.player().getZ(), ctx.player().getYRot(), 0xFF66CCFF);
		renderer.roundedRectOutline(x - 2, y - 2, SIZE + 4, SIZE + 4, 8, 1.5f, 0xFFAAAAAA);
	}
}

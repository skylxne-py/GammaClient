package dev.gamma.gui.tooltip;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * Draws the actual map image in the tooltip.
 *
 * <p>The map's pixels already exist on the GPU — vanilla uploads every known map to a
 * {@code DynamicTexture} so it can be drawn in item frames and in-hand.
 * {@code MapTextureManager.prepareMapTexture} is the public entry point that ensures the upload
 * is current and hands back its {@link Identifier}, so this is a plain textured blit rather than
 * anything that has to decode map colours itself.
 *
 * <p>Nothing is drawn for a map the client has no data for — an unexplored or never-received map
 * would otherwise blit whatever the texture happened to contain. {@link #getHeight} returns 0 in
 * that case so the tooltip doesn't reserve space for an image that never appears.
 */
public final class ClientMapPreviewTooltip implements ClientTooltipComponent {

	/** Native map resolution; also the blit's source dimensions. */
	private static final int MAP_PIXELS = 128;

	/** Drawn size. Full 128px is a third of a 1080p screen at GUI scale 2 — two thirds still reads clearly. */
	private static final int DRAWN_SIZE = 84;

	private static final int PADDING = 2;
	private static final int BORDER_COLOR = 0xFF8B8B8B;

	private final MapItemSavedData data;
	private final Identifier texture;

	public ClientMapPreviewTooltip(MapPreviewTooltip tooltip) {
		Minecraft client = Minecraft.getInstance();
		MapItemSavedData savedData = client.level == null ? null : client.level.getMapData(tooltip.mapId());
		this.data = savedData;
		this.texture = savedData == null ? null : client.getMapTextureManager().prepareMapTexture(tooltip.mapId(), savedData);
	}

	@Override
	public int getWidth(Font font) {
		return texture == null ? 0 : DRAWN_SIZE + PADDING * 2;
	}

	@Override
	public int getHeight(Font font) {
		return texture == null ? 0 : DRAWN_SIZE + PADDING * 2;
	}

	@Override
	public void extractImage(Font font, int x, int y, int width, int height, GuiGraphicsExtractor extractor) {
		if (texture == null || data == null) {
			return;
		}
		extractor.fill(x, y, x + DRAWN_SIZE + PADDING * 2, y + DRAWN_SIZE + PADDING * 2, BORDER_COLOR);
		// The region size (the 9th/10th arguments) must be the full 128, not the drawn size. The
		// shorter overload used here previously takes no region and defaults it to the drawn width
		// and height, so an 84px draw sampled an 84x84 region out of the 128x128 map — the top-left
		// 43% of the image, at 1:1 rather than scaled down. That is what "only the upper-left
		// quarter of the map shows" was.
		extractor.blit(RenderPipelines.GUI_TEXTURED, texture,
				x + PADDING, y + PADDING, 0.0f, 0.0f,
				DRAWN_SIZE, DRAWN_SIZE, MAP_PIXELS, MAP_PIXELS, MAP_PIXELS, MAP_PIXELS);
	}
}

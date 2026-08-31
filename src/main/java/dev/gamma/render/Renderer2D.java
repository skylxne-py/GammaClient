package dev.gamma.render;

import dev.gamma.render.pipeline.GammaPipelines;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * GUI-space drawing. Wraps a single frame's {@link GuiGraphicsExtractor} (the {@code
 * GuiGraphics} successor in 26.2 — obtained from {@code Screen.extractRenderState}, wired up
 * once the ClickGUI/HUD exist in Phase 3).
 *
 * <p>Rounded rects and circles are real geometry via analytic-coverage scanline
 * rasterization — many {@code fill()} calls, with the boundary pixel of each scanline
 * alpha-blended by its fractional coverage of the curve — rather than a custom SDF fragment
 * shader. See the design notes (2026-07-28) for why: the only public path for
 * arbitrary custom vertex data in GUI space ({@code SubmitNodeCollector.submitCustomGeometry})
 * still requires an existing {@code RenderType}, whose construction is package-private in
 * 26.2 — the same wall {@link Renderer3D} hit in world space. Draw-call volume here is GUI
 * widget-scale (dozens per frame), not per-entity, so the extra {@code fill()} calls are
 * immaterial.
 */
public final class Renderer2D {

	private final GuiGraphicsExtractor extractor;
	private final Deque<int[]> scissorStack = new ArrayDeque<>();
	private boolean textShadow = true;

	public Renderer2D(GuiGraphicsExtractor extractor) {
		this.extractor = extractor;
	}

	// -- flat fills --------------------------------------------------------

	public void fill(int x1, int y1, int x2, int y2, int color) {
		extractor.fill(GammaPipelines.GUI_SOLID, x1, y1, x2, y2, color);
	}

	/** Same as {@link #fill} but with blending off — cheaper for fully opaque backgrounds. */
	public void fillOpaque(int x1, int y1, int x2, int y2, int color) {
		extractor.fill(GammaPipelines.GUI_SOLID_OPAQUE, x1, y1, x2, y2, color);
	}

	public void gradient(int x1, int y1, int x2, int y2, int colorFrom, int colorTo) {
		extractor.fillGradient(x1, y1, x2, y2, colorFrom, colorTo);
	}

	// -- rounded rects / circles --------------------------------------------

	public void roundedRect(int x, int y, int width, int height, int radius, int color) {
		int r = clampRadius(radius, width, height);
		if (r == 0) {
			fill(x, y, x + width, y + height, color);
			return;
		}
		for (int i = 0; i < r; i++) {
			rasterizeCornerRow(x, y + i, width, r, i, color);
			rasterizeCornerRow(x, y + height - 1 - i, width, r, i, color);
		}
		if (height - 2 * r > 0) {
			fill(x, y + r, x + width, y + height - r, color);
		}
	}

	public void circle(int centerX, int centerY, int radius, int color) {
		roundedRect(centerX - radius, centerY - radius, radius * 2, radius * 2, radius, color);
	}

	public void roundedRectOutline(int x, int y, int width, int height, int radius, float strokeWidth, int color) {
		int r = clampRadius(radius, width, height);
		int sw = Math.max(1, Math.round(strokeWidth));
		if (r == 0) {
			outlineRect(x, y, width, height, sw, color);
			return;
		}
		int innerRadius = Math.max(0, r - sw);
		for (int i = 0; i < r; i++) {
			rasterizeCornerRingRow(x, y + i, width, r, innerRadius, i, color);
			rasterizeCornerRingRow(x, y + height - 1 - i, width, r, innerRadius, i, color);
		}
		if (height - 2 * r > 0) {
			fill(x, y + r, x + sw, y + height - r, color);
			fill(x + width - sw, y + r, x + width, y + height - r, color);
		}
		fill(x + r, y, x + width - r, y + sw, color);
		fill(x + r, y + height - sw, x + width - r, y + height, color);
	}

	private static int clampRadius(int radius, int width, int height) {
		return Math.max(0, Math.min(radius, Math.min(width, height) / 2));
	}

	/** One scanline of a filled rounded corner, with analytic coverage AA on the boundary pixel. */
	private void rasterizeCornerRow(int x, int rowY, int width, int radius, int rowIndex, int color) {
		double dy = radius - (rowIndex + 0.5);
		double halfExtent = Math.sqrt(Math.max(0.0, (double) radius * radius - dy * dy));
		double insetExact = radius - halfExtent;
		int insetFloor = (int) Math.floor(insetExact);
		double coverage = 1.0 - (insetExact - insetFloor);

		int leftBoundary = x + insetFloor;
		int rightBoundary = x + width - insetFloor - 1;
		if (leftBoundary < rightBoundary) {
			fill(leftBoundary + 1, rowY, rightBoundary, rowY + 1, color);
			fill(leftBoundary, rowY, leftBoundary + 1, rowY + 1, withAlphaScale(color, coverage));
			fill(rightBoundary, rowY, rightBoundary + 1, rowY + 1, withAlphaScale(color, coverage));
		} else if (leftBoundary == rightBoundary) {
			fill(leftBoundary, rowY, leftBoundary + 1, rowY + 1, withAlphaScale(color, coverage));
		}
	}

	/**
	 * One scanline of a stroked rounded corner (the ring between an outer and inner radius,
	 * sharing the same corner center). Rows above the inner arc's height are fully solid, like
	 * a filled corner; boundary AA is applied on the outer edge only — an accepted
	 * simplification, not an oversight, to keep this tractable (see the design notes).
	 */
	private void rasterizeCornerRingRow(int x, int rowY, int width, int outerRadius, int innerRadius, int rowIndex, int color) {
		int flatRows = outerRadius - innerRadius;
		if (rowIndex < flatRows) {
			rasterizeCornerRow(x, rowY, width, outerRadius, rowIndex, color);
			return;
		}
		int innerRowIndex = rowIndex - flatRows;
		double innerDy = innerRadius - (innerRowIndex + 0.5);
		double innerHalfExtent = Math.sqrt(Math.max(0.0, (double) innerRadius * innerRadius - innerDy * innerDy));
		int innerInsetFloor = (int) Math.floor(innerRadius - innerHalfExtent);

		double outerDy = outerRadius - (rowIndex + 0.5);
		double outerHalfExtent = Math.sqrt(Math.max(0.0, (double) outerRadius * outerRadius - outerDy * outerDy));
		int outerInsetFloor = (int) Math.floor(outerRadius - outerHalfExtent);

		int leftBandStart = x + outerInsetFloor;
		int leftBandEnd = x + innerInsetFloor + 1;
		if (leftBandEnd > leftBandStart) {
			fill(leftBandStart, rowY, leftBandEnd, rowY + 1, color);
		}
		int rightBandStart = x + width - innerInsetFloor - 1;
		int rightBandEnd = x + width - outerInsetFloor;
		if (rightBandEnd > rightBandStart) {
			fill(rightBandStart, rowY, rightBandEnd, rowY + 1, color);
		}
	}

	private void outlineRect(int x, int y, int width, int height, int strokeWidth, int color) {
		fill(x, y, x + width, y + strokeWidth, color);
		fill(x, y + height - strokeWidth, x + width, y + height, color);
		fill(x, y + strokeWidth, x + strokeWidth, y + height - strokeWidth, color);
		fill(x + width - strokeWidth, y + strokeWidth, x + width, y + height - strokeWidth, color);
	}

	private static int withAlphaScale(int argb, double scale) {
		int alpha = (argb >>> 24) & 0xFF;
		int scaled = Math.max(0, Math.min(255, (int) Math.round(alpha * scale)));
		return (scaled << 24) | (argb & 0x00FFFFFF);
	}

	// -- lines ----------------------------------------------------------------

	/** Arbitrary-angle line via stepped square rasterization — real geometry, no rotated quad. */
	public void line(double x1, double y1, double x2, double y2, float width, int color) {
		double dx = x2 - x1;
		double dy = y2 - y1;
		double length = Math.sqrt(dx * dx + dy * dy);
		if (length < 1.0e-4) {
			return;
		}
		int steps = Math.max(1, (int) Math.ceil(length));
		double stepX = dx / steps;
		double stepY = dy / steps;
		int half = Math.max(1, Math.round(width / 2f));
		for (int i = 0; i <= steps; i++) {
			int px = (int) Math.round(x1 + stepX * i);
			int py = (int) Math.round(y1 + stepY * i);
			fill(px - half, py - half, px + half, py + half, color);
		}
	}

	public void horizontalLine(int x1, int x2, int y, int color) {
		extractor.horizontalLine(x1, x2, y, color);
	}

	public void verticalLine(int x, int y1, int y2, int color) {
		extractor.verticalLine(x, y1, y2, color);
	}

	// -- text -------------------------------------------------------------

	/**
	 * Whether unqualified {@link #text} draws vanilla's offset drop shadow.
	 *
	 * <p>A per-frame switch on the renderer rather than a lookup inside it, because the thing that
	 * decides it — the active GUI style — lives above this layer, and {@code render} has no
	 * business knowing about {@code gui}. The GUI sets it once when it takes a renderer for the
	 * frame; callers that genuinely want a shadow either way keep using the six-argument overload.
	 */
	public void setTextShadow(boolean textShadow) {
		this.textShadow = textShadow;
	}

	public void text(Font font, String text, int x, int y, int color) {
		extractor.text(font, text, x, y, color, textShadow);
	}

	public void text(Font font, String text, int x, int y, int color, boolean dropShadow) {
		extractor.text(font, text, x, y, color, dropShadow);
	}

	// -- textures -----------------------------------------------------------

	/**
	 * Draws a whole registered texture stretched into the given box, tinted by {@code color}
	 * (use {@code 0xFFFFFFFF} for untouched, or vary the alpha to fade it).
	 *
	 * <p>The one caller so far is the album art in the Spotify overlay, whose source images are
	 * an arbitrary size decided by Spotify. Passing {@code width}/{@code height} as both the drawn
	 * size and the region size, with the texture's own dimensions as the sheet size, is what makes
	 * that a plain "fit the image to this box" — the sampler does the scaling, so a 300px cover
	 * and a 64px one both land correctly without the caller resampling anything.
	 */
	public void texture(Identifier texture, int x, int y, int width, int height, int textureWidth, int textureHeight, int color) {
		extractor.blit(GammaPipelines.GUI_TEXTURED, texture, x, y, 0.0f, 0.0f, width, height, textureWidth, textureHeight, textureWidth, textureHeight, color);
	}

	// -- items --------------------------------------------------------------

	public void item(net.minecraft.world.item.ItemStack stack, int x, int y) {
		extractor.item(stack, x, y);
	}

	public void itemDecorations(Font font, net.minecraft.world.item.ItemStack stack, int x, int y) {
		extractor.itemDecorations(font, stack, x, y);
	}

	// -- scissor stack ------------------------------------------------------

	/** Intersects with the current scissor (if any) and pushes the result. */
	public void pushScissor(int x1, int y1, int x2, int y2) {
		if (!scissorStack.isEmpty()) {
			int[] parent = scissorStack.peek();
			x1 = Math.max(x1, parent[0]);
			y1 = Math.max(y1, parent[1]);
			x2 = Math.min(x2, parent[2]);
			y2 = Math.min(y2, parent[3]);
		}
		x2 = Math.max(x1, x2);
		y2 = Math.max(y1, y2);
		scissorStack.push(new int[] {x1, y1, x2, y2});
		extractor.enableScissor(x1, y1, x2, y2);
	}

	/**
	 * Exactly one {@code disableScissor} per {@code pushScissor}, always.
	 *
	 * <p>{@code GuiGraphicsExtractor.enableScissor}/{@code disableScissor} are a push/pop pair over
	 * the extractor's own stack, not a set/clear pair. This used to "restore the parent" on the
	 * non-empty branch by calling {@code enableScissor} again — which pushed a third entry instead
	 * of replacing the second, and nothing ever popped it. A panel containing an expanded module
	 * row (panel scissor + row scissor) therefore leaked one live scissor per frame, clipped to
	 * that row's settings area, and everything drawn after the panels was silently cut down to it.
	 * The colour picker was the visible casualty: an expanded row is the only way to reach a colour
	 * swatch, so the picker always opened straight into the leaked rectangle and vanished.
	 *
	 * <p>The extractor's stack restores the parent by itself on pop, so there is nothing to
	 * reinstate here. The local stack is kept only to intersect a child rect with its parent before
	 * pushing.
	 */
	public void popScissor() {
		if (scissorStack.isEmpty()) {
			return;
		}
		scissorStack.pop();
		extractor.disableScissor();
	}

	// -- blur/backdrop ------------------------------------------------------

	/**
	 * Marks the blur boundary for the current GUI stratum — call before drawing the content
	 * that should blur what's already been drawn behind it (e.g. before a panel background).
	 */
	public void blurBackdrop() {
		extractor.blurBeforeThisStratum();
	}
}

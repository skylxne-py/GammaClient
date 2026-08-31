package dev.gamma.gui.clickgui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.gamma.render.Renderer2D;
import dev.gamma.util.ColorUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.function.IntConsumer;

/**
 * The shared color picker overlay: HSV square (saturation/value), a hue strip, an alpha strip,
 * and a hex input— a full picker rather than a hex field alone. Takes a plain getter/
 * setter pair rather than a {@code ColorSetting} directly so both the ClickGUI's {@code
 * ColorSettingWidget} and the HUD editor's per-element color control can share it. Owned and
 * positioned by whichever screen opens it, which routes input to it ahead of everything else
 * while open and closes it on outside-click/Escape.
 */
public final class ColorPickerPopup {

	private static final int SQUARE_SIZE = 100;
	private static final int STRIP_WIDTH = 14;
	private static final int GAP = 8;
	private static final int ALPHA_HEIGHT = 14;
	private static final int HEX_HEIGHT = 18;
	public static final int WIDTH = SQUARE_SIZE + GAP + STRIP_WIDTH;
	public static final int HEIGHT = SQUARE_SIZE + GAP + ALPHA_HEIGHT + GAP + HEX_HEIGHT;

	/**
	 * The backdrop is drawn inset by this much on every side, and the hue/alpha markers
	 * deliberately overhang their strips into it. {@code WIDTH}/{@code HEIGHT} describe the
	 * <em>contents</em>, so every bounds calculation has to add this back — getting that wrong is
	 * what let the popup hang off the screen edge.
	 */
	private static final int PADDING = GAP;

	/** Gap kept between the popup's outer edge and the screen edge. */
	private static final int SCREEN_MARGIN = 4;

	/**
	 * Size of the whole painted popup, padding included — {@link #WIDTH}/{@link #HEIGHT} describe
	 * only the contents. Callers placing the popup need these, since placing by content size puts
	 * the border off-screen.
	 */
	public static int outerWidth() {
		return WIDTH + PADDING * 2;
	}

	public static int outerHeight() {
		return HEIGHT + PADDING * 2;
	}

	/** Distance from the content origin to the painted edge, for callers converting between the two. */
	public static int padding() {
		return PADDING;
	}

	private final IntConsumer setter;
	private final Theme theme;
	private int x;
	private int y;

	private double hue;
	private double saturation;
	private double value;
	private int alpha;

	private boolean draggingSquare;
	private boolean draggingHue;
	private boolean draggingAlpha;
	private StringBuilder hexBuffer;
	private boolean hexFocused;

	public ColorPickerPopup(int initial, IntConsumer setter, Theme theme, int anchorX, int anchorY) {
		this.setter = setter;
		this.theme = theme;
		this.x = anchorX;
		this.y = anchorY;
		double[] hsv = ColorUtil.toHsv(initial);
		this.hue = hsv[0];
		this.saturation = hsv[1];
		this.value = hsv[2];
		this.alpha = ColorUtil.alpha(initial);
		this.hexBuffer = new StringBuilder(ColorUtil.toHex(initial, false));
	}

	public boolean isCapturingInput() {
		return hexFocused;
	}

	/**
	 * Clamps the popup on-screen — called each frame since the screen it's anchored in can resize.
	 *
	 * <p>The bound is the painted edge, {@link #PADDING} outside the content box, not the content
	 * box itself. Clamping to the content box (as this used to) left the backdrop, and the hue and
	 * alpha markers that overhang into it, spilling {@code PADDING} pixels past the screen on the
	 * right and bottom and clipped on the left and top — a picker opened from a swatch near any
	 * screen edge came out visibly cut off.
	 *
	 * <p>{@code max} is applied outermost so that on a screen too narrow to fit the popup at all,
	 * the top-left stays visible rather than the whole thing sliding off the other way.
	 */
	public void clampToScreen(int screenWidth, int screenHeight) {
		int margin = PADDING + SCREEN_MARGIN;
		x = Math.max(margin, Math.min(x, screenWidth - WIDTH - margin));
		y = Math.max(margin, Math.min(y, screenHeight - HEIGHT - margin));
	}

	/**
	 * Covers the whole painted area, padding included. This is what decides an outside-click
	 * closes the popup, so a click on the visible backdrop margin must count as inside — with the
	 * old content-box test, clicking the popup's own border dismissed it.
	 */
	public boolean contains(double mouseX, double mouseY) {
		return mouseX >= x - PADDING && mouseX < x + WIDTH + PADDING
				&& mouseY >= y - PADDING && mouseY < y + HEIGHT + PADDING;
	}

	public void render(Renderer2D renderer, Font font) {
		renderer.roundedRect(x - PADDING, y - PADDING, WIDTH + PADDING * 2, HEIGHT + PADDING * 2, 10, theme.panelBackground());

		renderSquare(renderer);
		renderHueStrip(renderer);
		renderAlphaStrip(renderer);
		renderHexField(renderer, font);
	}

	private void renderSquare(Renderer2D renderer) {
		int stripe = 2;
		for (int col = 0; col < SQUARE_SIZE; col += stripe) {
			double s = col / (double) SQUARE_SIZE;
			int top = ColorUtil.fromHsv(hue, s, 1.0, 255);
			int bottom = ColorUtil.fromHsv(hue, s, 0.0, 255);
			renderer.gradient(x + col, y, x + col + stripe, y + SQUARE_SIZE, top, bottom);
		}
		int markerX = x + (int) Math.round(saturation * SQUARE_SIZE);
		int markerY = y + (int) Math.round((1.0 - value) * SQUARE_SIZE);
		renderer.roundedRectOutline(markerX - 4, markerY - 4, 8, 8, 4, 1.5f, 0xFFFFFFFF);
	}

	private void renderHueStrip(Renderer2D renderer) {
		int stripX = x + SQUARE_SIZE + GAP;
		int rowHeight = 2;
		for (int row = 0; row < SQUARE_SIZE; row += rowHeight) {
			double h = row / (double) SQUARE_SIZE * 360.0;
			int color = ColorUtil.fromHsv(h, 1.0, 1.0, 255);
			renderer.fill(stripX, y + row, stripX + STRIP_WIDTH, y + row + rowHeight, color);
		}
		int markerY = y + (int) Math.round(hue / 360.0 * SQUARE_SIZE);
		renderer.fill(stripX - 2, markerY - 1, stripX + STRIP_WIDTH + 2, markerY + 1, 0xFFFFFFFF);
	}

	private void renderAlphaStrip(Renderer2D renderer) {
		int stripY = y + SQUARE_SIZE + GAP;
		int rgb = ColorUtil.fromHsv(hue, saturation, value, 255);
		int checkerSize = 4;
		for (int col = 0; col < WIDTH; col += checkerSize) {
			for (int row = 0; row < ALPHA_HEIGHT; row += checkerSize) {
				boolean light = ((col / checkerSize) + (row / checkerSize)) % 2 == 0;
				renderer.fill(x + col, stripY + row, Math.min(x + col + checkerSize, x + WIDTH), Math.min(stripY + row + checkerSize, stripY + ALPHA_HEIGHT), light ? 0xFFBBBBBB : 0xFF888888);
			}
		}
		renderer.gradient(x, stripY, x + WIDTH, stripY + ALPHA_HEIGHT, ColorUtil.withAlpha(rgb, 0), ColorUtil.withAlpha(rgb, 255));
		int markerX = x + (int) Math.round(alpha / 255.0 * WIDTH);
		renderer.fill(markerX - 1, stripY - 2, markerX + 1, stripY + ALPHA_HEIGHT + 2, 0xFFFFFFFF);
	}

	private void renderHexField(Renderer2D renderer, Font font) {
		int fieldY = y + SQUARE_SIZE + GAP + ALPHA_HEIGHT + GAP;
		renderer.roundedRect(x, fieldY, WIDTH, HEX_HEIGHT, 5, theme.settingsBackground());
		if (hexFocused) {
			renderer.roundedRectOutline(x, fieldY, WIDTH, HEX_HEIGHT, 5, 1.5f, theme.accent());
		}
		renderer.text(font, "#" + hexBuffer, x + 6, fieldY + (HEX_HEIGHT - font.lineHeight) / 2, theme.textPrimary());
	}

	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) {
			return false;
		}
		double mx = event.x();
		double my = event.y();
		if (mx >= x && mx < x + SQUARE_SIZE && my >= y && my < y + SQUARE_SIZE) {
			draggingSquare = true;
			updateSquare(mx, my);
			return true;
		}
		int stripX = x + SQUARE_SIZE + GAP;
		if (mx >= stripX && mx < stripX + STRIP_WIDTH && my >= y && my < y + SQUARE_SIZE) {
			draggingHue = true;
			updateHue(my);
			return true;
		}
		int stripY = y + SQUARE_SIZE + GAP;
		if (mx >= x && mx < x + WIDTH && my >= stripY && my < stripY + ALPHA_HEIGHT) {
			draggingAlpha = true;
			updateAlpha(mx);
			return true;
		}
		int fieldY = y + SQUARE_SIZE + GAP + ALPHA_HEIGHT + GAP;
		if (mx >= x && mx < x + WIDTH && my >= fieldY && my < fieldY + HEX_HEIGHT) {
			hexFocused = true;
			return true;
		}
		hexFocused = false;
		return contains(mx, my);
	}

	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (draggingSquare) {
			updateSquare(event.x(), event.y());
			return true;
		}
		if (draggingHue) {
			updateHue(event.y());
			return true;
		}
		if (draggingAlpha) {
			updateAlpha(event.x());
			return true;
		}
		return false;
	}

	public boolean mouseReleased(MouseButtonEvent event) {
		boolean wasDragging = draggingSquare || draggingHue || draggingAlpha;
		draggingSquare = false;
		draggingHue = false;
		draggingAlpha = false;
		return wasDragging;
	}

	public boolean charTyped(CharacterEvent event) {
		if (!hexFocused) {
			return false;
		}
		String s = event.codepointAsString();
		if (s.matches("[0-9a-fA-F]") && hexBuffer.length() < 6) {
			hexBuffer.append(s);
		}
		return true;
	}

	public boolean keyPressed(KeyEvent event) {
		if (!hexFocused) {
			return false;
		}
		if (event.key() == InputConstants.KEY_BACKSPACE && hexBuffer.length() > 0) {
			hexBuffer.deleteCharAt(hexBuffer.length() - 1);
			return true;
		}
		if (event.key() == InputConstants.KEY_RETURN && hexBuffer.length() == 6) {
			int rgb = ColorUtil.parseHex(hexBuffer.toString(), alpha);
			double[] hsv = ColorUtil.toHsv(rgb);
			hue = hsv[0];
			saturation = hsv[1];
			value = hsv[2];
			commit();
			hexFocused = false;
			return true;
		}
		if (event.key() == InputConstants.KEY_ESCAPE) {
			hexFocused = false;
			return true;
		}
		return false;
	}

	private void updateSquare(double mx, double my) {
		saturation = clamp01((mx - x) / SQUARE_SIZE);
		value = 1.0 - clamp01((my - y) / SQUARE_SIZE);
		syncHex();
		commit();
	}

	private void updateHue(double my) {
		hue = clamp01((my - y) / SQUARE_SIZE) * 360.0;
		syncHex();
		commit();
	}

	private void updateAlpha(double mx) {
		alpha = (int) Math.round(clamp01((mx - x) / WIDTH) * 255);
		commit();
	}

	private void syncHex() {
		hexBuffer = new StringBuilder(ColorUtil.toHex(ColorUtil.fromHsv(hue, saturation, value, 255), false));
	}

	private void commit() {
		setter.accept(ColorUtil.fromHsv(hue, saturation, value, alpha));
	}

	private static double clamp01(double v) {
		return Math.max(0.0, Math.min(1.0, v));
	}
}

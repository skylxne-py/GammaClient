package dev.gamma.gui.hud.elements;

import dev.gamma.gui.clickgui.Theme;
import dev.gamma.gui.hud.Anchor;
import dev.gamma.gui.hud.HudComponent;
import dev.gamma.gui.hud.HudContext;
import dev.gamma.modules.misc.Spotify;
import dev.gamma.modules.misc.spotify.AlbumArt;
import dev.gamma.modules.misc.spotify.SpotifyClient;
import dev.gamma.modules.misc.spotify.SpotifyState;
import dev.gamma.modules.misc.spotify.SpotifyTrack;
import dev.gamma.render.Renderer2D;
import dev.gamma.util.ColorUtil;
import net.minecraft.client.gui.Font;

/**
 * The now-playing overlay: cover, title, artist, a progress bar, and transport controls.
 *
 * <p>Bottom-right by default and draggable/scalable like every other {@link HudComponent}, so
 * "resizable in the HUD editor" needs nothing special here — scroll over it in the editor and the
 * whole element scales, because {@code HudManager} scales the transform rather than the layout.
 * Accent colours come from the shared {@link Theme}, so the overlay follows the client palette
 * rather than needing to be recoloured on its own.
 *
 * <h2>Why the button bounds are published</h2>
 *
 * <p>A HUD element is drawn, never clicked: in-game the cursor is captured, so there is no pointer
 * to hit-test against. Clicks are therefore handled by {@link Spotify} while some other screen is
 * open (inventory, chat, pause — the HUD still draws underneath those), and it needs to know where
 * the buttons ended up. Rather than duplicate the layout arithmetic on the input side, the layout
 * is computed once here in {@link #boundsOf} and both sides read it.
 */
public final class SpotifyElement extends HudComponent {

	private static final int PADDING = 7;
	private static final int ART = 40;
	private static final int GAP = 8;
	private static final int BUTTON_WIDTH = 14;
	private static final int BUTTON_HEIGHT = 12;
	private static final int BUTTON_GAP = 4;
	private static final int PROGRESS_HEIGHT = 4;

	// -- vertical layout -----------------------------------------------------
	// One block, because every row's position depends on the ones above it and the height depends
	// on all of them. The previous version had these as magic numbers spread across the render
	// methods, and they had drifted into each other: the progress row's text ran from y+32 to y+41
	// while the buttons started at y+37, so the elapsed time was drawn underneath the previous-track
	// icon. Deriving HEIGHT from the last row rather than asserting it separately is what stops that
	// happening again -- a row that moves now pushes the element's height instead of overrunning it.

	private static final int TITLE_Y = 13;
	private static final int ARTIST_Y = 25;
	/** Top of the progress bar. Its labels are centred on it, so they reach {@code lineHeight/2} either side. */
	private static final int PROGRESS_Y = 38;
	/** Clear of the progress labels' descent by six pixels — the gap the controls were missing. */
	private static final int BUTTON_Y = 51;
	private static final int HEIGHT = BUTTON_Y + BUTTON_HEIGHT + PADDING;
	private static final int ART_Y = (HEIGHT - ART) / 2;

	/** Which control a point falls on. */
	public enum Button {
		PREVIOUS,
		PLAY_PAUSE,
		NEXT
	}

	public SpotifyElement() {
		super("spotify", "Spotify", Anchor.BOTTOM_RIGHT, 6, 6);
		setColor(Theme.DEFAULT_ACCENT);
		setEnabled(false);
	}

	@Override
	public int measureWidth(Font font, HudContext ctx) {
		return width();
	}

	@Override
	public int measureHeight(Font font, HudContext ctx) {
		return HEIGHT;
	}

	private static int width() {
		Spotify module = Spotify.instance;
		return module == null ? 170 : module.overlayWidth();
	}

	/** The element-local { {x, y, width, height}} box of one control. */
	public static int[] boundsOf(Button button) {
		int textX = PADDING + ART + GAP;
		return switch (button) {
			case PREVIOUS -> new int[]{textX, BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT};
			case PLAY_PAUSE -> new int[]{textX + BUTTON_WIDTH + BUTTON_GAP, BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT};
			case NEXT -> new int[]{textX + (BUTTON_WIDTH + BUTTON_GAP) * 2, BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT};
		};
	}

	/** The control containing an element-local point, or {@code null}. */
	public static Button buttonAt(double localX, double localY) {
		for (Button button : Button.values()) {
			int[] box = boundsOf(button);
			if (localX >= box[0] && localX < box[0] + box[2] && localY >= box[1] && localY < box[1] + box[3]) {
				return button;
			}
		}
		return null;
	}

	@Override
	public void render(Renderer2D renderer, Font font, int x, int y, HudContext ctx) {
		Spotify module = Spotify.instance;
		// The module owns whether this shows. Checked here as well as via the enabled flag because
		// that flag is persisted in hud.json and the module's own state is not, so a session can
		// start with the two disagreeing.
		if (module == null || !module.isEnabled()) {
			return;
		}
		SpotifyState state = module.state();
		int w = width();
		// The shared client accent, not this element's own colour: the progress bar should follow the
		// palette everything else follows, rather than being one more thing to recolour separately.
		int accent = Theme.instance == null ? Theme.DEFAULT_ACCENT : Theme.instance.accent();
		int background = ColorUtil.argb((int) Math.round(255 * module.opacity()), 0, 0, 0);

		renderer.roundedRect(x, y, w, HEIGHT, 8, background);

		if (state.status() != SpotifyState.Status.PLAYING) {
			renderPlaceholder(renderer, font, x, y, w, accent, state);
			return;
		}

		SpotifyTrack track = state.track();
		renderArt(renderer, x + PADDING, y + ART_Y, track.artUrl(), accent);

		int textX = x + PADDING + ART + GAP;
		int textWidth = w - (PADDING + ART + GAP) - PADDING;
		renderer.text(font, trim(font, track.title(), textWidth), textX, y + TITLE_Y, 0xFFF2F2F5);
		renderer.text(font, trim(font, track.artist(), textWidth), textX, y + ARTIST_Y, 0xFF9A9AA4);

		renderProgress(renderer, font, textX, y + PROGRESS_Y, textWidth, state, accent);
		renderControls(renderer, x, y, state, accent, module);
	}

	private void renderPlaceholder(Renderer2D renderer, Font font, int x, int y, int w, int accent, SpotifyState state) {
		renderer.roundedRect(x + PADDING, y + ART_Y, ART, ART, 4, 0x33FFFFFF);
		int textX = x + PADDING + ART + GAP;
		int textWidth = w - (PADDING + ART + GAP) - PADDING;
		// Two lines rather than four, so they are centred on the element instead of using the
		// playing layout's rows and leaving a hole where the controls would be.
		int block = HEIGHT / 2 - font.lineHeight - 1;
		renderer.text(font, "Spotify", textX, y + block, accent);
		renderer.text(font, trim(font, state.message(), textWidth), textX, y + block + 12, 0xFF9A9AA4);
	}

	private void renderArt(Renderer2D renderer, int x, int y, String artUrl, int accent) {
		AlbumArt.Loaded art = AlbumArt.textureFor(artUrl);
		if (art == null) {
			// Not an error state -- a cover is one HTTP request behind the track change, so this
			// is what the first frame or two of every new song looks like.
			renderer.roundedRect(x, y, ART, ART, 4, 0x33FFFFFF);
			renderer.roundedRectOutline(x, y, ART, ART, 4, 1f, ColorUtil.scaleAlpha(accent, 0.5));
			return;
		}
		renderer.texture(art.id(), x, y, ART, ART, art.width(), art.height(), 0xFFFFFFFF);
	}

	/**
	 * Track, fill, playhead, and the elapsed/remaining times either side.
	 *
	 * <p>The times sit outside the bar rather than under it because the bar is only four pixels tall
	 * and the element is only 58: a third row of text would cost more height than the information is
	 * worth. Remaining time is shown as {@code -1:23} rather than the track length, which is the
	 * number you actually want off a progress bar.
	 *
	 * <h2>Why both labels are derived from one number</h2>
	 *
	 * <p>The obvious way to write this — {@code progress / 1000} for one label and
	 * {@code (duration - progress) / 1000} for the other — makes the two tick over at different
	 * moments. A track is essentially never a whole number of seconds long, so the two divisions
	 * land on grids offset by the remainder: at 2:47.4 into a 3:31.6 track, elapsed rolls to 2:48
	 * six tenths before remaining rolls to -0:43. It looks like one of them is lagging, because one
	 * of them is. Rounding the length once and subtracting integer seconds from integer seconds
	 * puts both on the same grid, so they always change on the same frame and always sum to the
	 * displayed length.
	 */
	private void renderProgress(Renderer2D renderer, Font font, int x, int y, int width, SpotifyState state, int accent) {
		long duration = state.track().durationMs();
		long progress = Math.min(duration, state.interpolatedProgressMs(System.nanoTime()));

		long totalSeconds = Math.max(0, duration) / 1000;
		long elapsedSeconds = Math.min(totalSeconds, Math.max(0, progress) / 1000);
		String elapsed = formatTime(elapsedSeconds);
		String remaining = "-" + formatTime(totalSeconds - elapsedSeconds);
		int elapsedWidth = font.width(elapsed);
		int remainingWidth = font.width(remaining);
		int barX = x + elapsedWidth + 5;
		int barWidth = width - elapsedWidth - remainingWidth - 10;

		int textY = y + (PROGRESS_HEIGHT - font.lineHeight) / 2;
		renderer.text(font, elapsed, x, textY, 0xFF7E7F8A);
		renderer.text(font, remaining, x + width - remainingWidth, textY, 0xFF7E7F8A);
		if (barWidth <= 2) {
			return;
		}

		renderer.roundedRect(barX, y, barWidth, PROGRESS_HEIGHT, PROGRESS_HEIGHT / 2, 0x33FFFFFF);
		if (duration <= 0) {
			return;
		}
		double fraction = Math.min(1.0, (double) progress / duration);
		int filled = (int) Math.round(barWidth * fraction);
		if (filled > 0) {
			renderer.roundedRect(barX, y, filled, PROGRESS_HEIGHT, PROGRESS_HEIGHT / 2, accent);
		}
		// A playhead makes a bar that moves a pixel a minute look like it is doing something, and
		// gives the accent somewhere solid to read against a mostly-empty track.
		renderer.circle(barX + filled, y + PROGRESS_HEIGHT / 2, 3, accent);
		renderer.circle(barX + filled, y + PROGRESS_HEIGHT / 2, 1, 0xFFFFFFFF);
	}

	/** Takes whole seconds, not millis — the caller has already put both labels on one grid. */
	private static String formatTime(long seconds) {
		return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
	}

	private void renderControls(Renderer2D renderer, int x, int y, SpotifyState state, int accent, Spotify module) {
		Button hovered = module.hoveredButton();
		int idle = 0xFFC8C8D0;

		int[] previous = boundsOf(Button.PREVIOUS);
		drawSkip(renderer, x + previous[0], y + previous[1], previous[2], previous[3], true, hovered == Button.PREVIOUS ? accent : idle);

		int[] play = boundsOf(Button.PLAY_PAUSE);
		int playColor = hovered == Button.PLAY_PAUSE ? accent : idle;
		if (state.playing()) {
			drawPause(renderer, x + play[0], y + play[1], play[2], play[3], playColor);
		} else {
			int cx = x + play[0] + play[2] / 2;
			int cy = y + play[1] + play[3] / 2;
			triangleRight(renderer, cx - 4, cy - 5, 9, 10, playColor);
		}

		int[] next = boundsOf(Button.NEXT);
		drawSkip(renderer, x + next[0], y + next[1], next[2], next[3], false, hovered == Button.NEXT ? accent : idle);

	}

	// -- icon geometry -------------------------------------------------------
	// All vector, no icon sheet: three shapes at a dozen pixels each are less code than a texture
	// plus the atlas/resource-pack plumbing to load one, and they stay crisp at any HUD scale.

	private void drawSkip(Renderer2D renderer, int x, int y, int w, int h, boolean backwards, int color) {
		int cx = x + w / 2;
		int cy = y + h / 2;
		if (backwards) {
			renderer.fill(cx - 5, cy - 5, cx - 3, cy + 5, color);
			triangleLeft(renderer, cx - 2, cy - 5, 7, 10, color);
		} else {
			triangleRight(renderer, cx - 5, cy - 5, 7, 10, color);
			renderer.fill(cx + 3, cy - 5, cx + 5, cy + 5, color);
		}
	}

	private void drawPause(Renderer2D renderer, int x, int y, int w, int h, int color) {
		int cx = x + w / 2;
		int cy = y + h / 2;
		renderer.fill(cx - 4, cy - 5, cx - 1, cy + 5, color);
		renderer.fill(cx + 1, cy - 5, cx + 4, cy + 5, color);
	}

	/** Base on the left edge, apex on the right; rows shortened toward the apex. */
	private void triangleRight(Renderer2D renderer, int x, int y, int w, int h, int color) {
		for (int row = 0; row < h; row++) {
			int length = rowLength(row, h, w);
			if (length > 0) {
				renderer.fill(x, y + row, x + length, y + row + 1, color);
			}
		}
	}

	private void triangleLeft(Renderer2D renderer, int x, int y, int w, int h, int color) {
		for (int row = 0; row < h; row++) {
			int length = rowLength(row, h, w);
			if (length > 0) {
				renderer.fill(x + w - length, y + row, x + w, y + row + 1, color);
			}
		}
	}

	/** Full width at the vertical middle, tapering to nothing at both ends. */
	private static int rowLength(int row, int height, int width) {
		double half = (height - 1) / 2.0;
		double distance = Math.abs(row - half) / Math.max(1.0, half);
		return (int) Math.round(width * (1.0 - distance));
	}

	// -- text ----------------------------------------------------------------

	private static String trim(Font font, String text, int maxWidth) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		if (font.width(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "...";
		int budget = maxWidth - font.width(ellipsis);
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			if (font.width(builder.toString() + text.charAt(i)) > budget) {
				break;
			}
			builder.append(text.charAt(i));
		}
		return builder + ellipsis;
	}

	/** Convenience for {@link Spotify}: the client's playback commands, in button order. */
	public static SpotifyClient.Command commandFor(Button button) {
		return switch (button) {
			case PREVIOUS -> SpotifyClient.Command.PREVIOUS;
			case PLAY_PAUSE -> SpotifyClient.Command.PLAY_PAUSE;
			case NEXT -> SpotifyClient.Command.NEXT;
		};
	}
}

package dev.gamma.gui.hud;

import dev.gamma.render.Renderer2D;
import dev.gamma.util.ColorUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * The find-notification stack: large centred text just above the hotbar, fading in and out.
 *
 * <h2>Why not vanilla's toasts</h2>
 *
 * <p>This started out as {@code SystemToast} in the top-right corner, which is what "toast" means
 * inside Minecraft's own code. It is not what it means here: the corner is where the game puts
 * things you are allowed to miss — an advancement, a tutorial hint — and it is the one part of the
 * screen you are never looking at while playing. A notification that a stash or a player just turned
 * up is the opposite kind of message. Above the hotbar is where the game puts things it actually
 * wants read (the held-item name, the action bar), because it is directly under the crosshair.
 *
 * <p>So this draws its own. Vanilla's toast column stays free for vanilla's toasts, and nothing here
 * has to fight it for a corner.
 *
 * <h2>Colour</h2>
 *
 * <p>Fixed red rather than the client accent, deliberately, even though the rest of the UI follows
 * the palette. This is the one thing on screen whose whole job is to be alarming, and an accent the
 * user is free to set to pale blue would quietly stop it doing that. The detail line underneath is
 * plain light grey so the red stays the thing your eye lands on.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #push} is called from database and packet callbacks, so the queue is concurrent and the
 * render pass only reads it. Nothing here touches the client thread on the way in — an entry is a
 * record of what happened and when, and every decision about how to draw it is made at draw time
 * from the current clock.
 */
public final class Notifications {

	/** Beyond this the oldest is dropped: a stack taller than this is unreadable before it is informative. */
	private static final int MAX_VISIBLE = 4;
	private static final long FADE_IN_MILLIS = 120;
	private static final long HOLD_MILLIS = 2_600;
	private static final long FADE_OUT_MILLIS = 420;
	private static final long LIFETIME_MILLIS = FADE_IN_MILLIS + HOLD_MILLIS + FADE_OUT_MILLIS;

	/** Big enough to read at a glance without becoming the screen. */
	private static final float TITLE_SCALE = 1.6f;
	/** Clears the hotbar, the health/armour rows and the experience bar. */
	private static final int BOTTOM_MARGIN = 72;
	private static final int ENTRY_GAP = 5;
	/** How far an entry slides up as it fades in. */
	private static final int RISE = 5;

	private static final int TITLE_COLOR = 0xFFFF4A4A;
	private static final int DETAIL_COLOR = 0xFFE2E2E8;

	private static final ConcurrentLinkedDeque<Entry> entries = new ConcurrentLinkedDeque<>();

	private record Entry(String title, String detail, long shownAtMillis) {
	}

	private Notifications() {
	}

	/** Queues a notification. Safe from any thread. */
	public static void push(String title, String detail) {
		entries.addLast(new Entry(title, detail == null ? "" : detail, System.currentTimeMillis()));
		while (entries.size() > MAX_VISIBLE) {
			entries.pollFirst();
		}
	}

	/** Drops everything currently showing — used when the world goes away and its finds stop meaning anything. */
	public static void clear() {
		entries.clear();
	}

	/**
	 * Draws the stack, newest nearest the hotbar.
	 *
	 * <p>Expiry happens here rather than on a timer because this is the only place that needs to know:
	 * an entry nobody has drawn has not been seen, and one drawn past its lifetime is simply not drawn
	 * again. With no world on screen the queue just sits there, which is what {@link #clear} is for.
	 */
	static void render(GuiGraphicsExtractor extractor, Renderer2D renderer, Font font, HudContext ctx) {
		long now = System.currentTimeMillis();
		entries.removeIf(entry -> now - entry.shownAtMillis() >= LIFETIME_MILLIS);
		if (entries.isEmpty()) {
			return;
		}

		// Newest first, so the stack can be laid out upward from a fixed bottom edge. Snapshotted
		// because the layout walks it twice over and push() can add from another thread mid-frame.
		List<Entry> newestFirst = new ArrayList<>(entries);
		java.util.Collections.reverse(newestFirst);

		int centerX = ctx.screenWidth() / 2;
		int titleHeight = Math.round(font.lineHeight * TITLE_SCALE);
		int bottom = ctx.screenHeight() - BOTTOM_MARGIN;

		for (Entry entry : newestFirst) {
			long age = now - entry.shownAtMillis();
			double appear = Math.min(1.0, age / (double) FADE_IN_MILLIS);
			long remaining = LIFETIME_MILLIS - age;
			double disappear = Math.min(1.0, remaining / (double) FADE_OUT_MILLIS);
			double alpha = Math.max(0.0, Math.min(appear, disappear));

			boolean hasDetail = !entry.detail().isEmpty();
			int blockHeight = titleHeight + (hasDetail ? font.lineHeight + 2 : 0);
			int top = bottom - blockHeight + (int) Math.round((1.0 - appear) * RISE);

			var pose = extractor.pose();
			pose.pushMatrix();
			pose.translate((float) centerX, (float) top);
			pose.scale(TITLE_SCALE);
			renderer.text(font, entry.title(), -font.width(entry.title()) / 2, 0,
					ColorUtil.scaleAlpha(TITLE_COLOR, alpha), true);
			pose.popMatrix();

			if (hasDetail) {
				renderer.text(font, entry.detail(), centerX - font.width(entry.detail()) / 2, top + titleHeight + 2,
						ColorUtil.scaleAlpha(DETAIL_COLOR, alpha), true);
			}
			bottom = bottom - blockHeight - ENTRY_GAP;
		}
	}
}

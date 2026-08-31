package dev.gamma.gui.hud.elements;

import dev.gamma.gui.hud.Anchor;
import dev.gamma.gui.hud.HudComponent;
import dev.gamma.gui.hud.HudContext;
import dev.gamma.modules.donutsmp.ShardItemTimer;
import dev.gamma.render.Renderer2D;
import dev.gamma.util.ExpirableItem;
import net.minecraft.client.gui.Font;

/**
 * Countdown for the held self-destructing item.
 *
 * <p>Draws nothing when nothing expirable is held, but still <em>measures</em> a placeholder so the
 * HUD editor keeps a grabbable outline — same approach as {@link SpotifyElement}, which is invisible
 * with no track playing and would otherwise be impossible to position.
 *
 * <p>Not a {@link dev.gamma.gui.hud.SingleLineHudComponent}: that renders whatever {@code text()}
 * returns unconditionally, and the whole point here is being absent most of the time.
 *
 * <p>All state comes from {@link ShardItemTimer}'s cached tick snapshot; the only per-frame work is
 * subtracting from the cached absolute deadline, which is what keeps the seconds ticking smoothly
 * rather than in the ~1-minute steps the server sends stack updates at.
 */
public final class ShardItemTimerElement extends HudComponent {

	/** Roughly "Shard Pickaxe  2h 24m 33s" — only used to size the editor handle when idle. */
	private static final String PLACEHOLDER = "Shard Pickaxe  0h 00m 00s";

	private static final int URGENT_COLOR = 0xFFFF5555;

	public ShardItemTimerElement() {
		// The persisted id stays "item_expiry" through the rename on purpose: it is the key
		// hud.json stores position, scale and enabled state under, and changing it would silently
		// reset anyone who had already moved this element. Only the label the editor shows moved.
		super("item_expiry", "Shard Item Timer", Anchor.BOTTOM_CENTER, 0, 79);
	}

	@Override
	public int measureWidth(Font font, HudContext ctx) {
		String text = text();
		return font.width(text != null ? text : PLACEHOLDER);
	}

	@Override
	public int measureHeight(Font font, HudContext ctx) {
		return font.lineHeight;
	}

	@Override
	public void render(Renderer2D renderer, Font font, int x, int y, HudContext ctx) {
		String text = text();
		if (text == null) {
			return;
		}
		renderer.text(font, text, x, y, urgent() ? URGENT_COLOR : color());
	}

	/** Null when there is nothing to show — module missing, disabled, or no expirable item held. */
	private static String text() {
		ShardItemTimer module = ShardItemTimer.instance;
		if (module == null || !module.isEnabled()) {
			return null;
		}
		ShardItemTimer.Snapshot snapshot = module.snapshot();
		if (snapshot == null) {
			return null;
		}
		String time = ExpirableItem.format(snapshot.remainingMillis(), module.showSeconds());
		return module.showName() ? snapshot.itemName() + "  " + time : time;
	}

	private static boolean urgent() {
		ShardItemTimer module = ShardItemTimer.instance;
		if (module == null) {
			return false;
		}
		ShardItemTimer.Snapshot snapshot = module.snapshot();
		long threshold = module.warnThresholdMillis();
		return snapshot != null && threshold > 0 && snapshot.remainingMillis() <= threshold;
	}
}

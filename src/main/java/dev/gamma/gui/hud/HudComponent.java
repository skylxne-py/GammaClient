package dev.gamma.gui.hud;

import dev.gamma.render.Renderer2D;
import net.minecraft.client.gui.Font;

/**
 * One draggable HUD overlay element. Position is an {@link Anchor} plus a pixel offset from it
 * (not raw screen coordinates) so elements stay put — and can snap to edges — across resolution
 * changes, per the roadmap's HUD editor requirements.
 */
public abstract class HudComponent {

	private final String id;
	private final String displayName;
	private boolean enabled = true;
	private Anchor anchor;
	private double offsetX;
	private double offsetY;
	private double scale = 1.0;
	private int color = 0xFFFFFFFF;

	protected HudComponent(String id, String displayName, Anchor defaultAnchor, double defaultOffsetX, double defaultOffsetY) {
		this.id = id;
		this.displayName = displayName;
		this.anchor = defaultAnchor;
		this.offsetX = defaultOffsetX;
		this.offsetY = defaultOffsetY;
	}

	public final String id() {
		return id;
	}

	public final String displayName() {
		return displayName;
	}

	public final boolean isEnabled() {
		return enabled;
	}

	public final void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public final Anchor anchor() {
		return anchor;
	}

	public final double offsetX() {
		return offsetX;
	}

	public final double offsetY() {
		return offsetY;
	}

	public final void setPosition(Anchor anchor, double offsetX, double offsetY) {
		this.anchor = anchor;
		this.offsetX = offsetX;
		this.offsetY = offsetY;
	}

	public final double scale() {
		return scale;
	}

	public final void setScale(double scale) {
		this.scale = Math.max(0.25, Math.min(4.0, scale));
	}

	public final int color() {
		return color;
	}

	public final void setColor(int color) {
		this.color = color;
	}

	/** Content width at scale 1 — {@link HudManager} scales the transform, not the measurement. */
	public abstract int measureWidth(Font font, HudContext ctx);

	public abstract int measureHeight(Font font, HudContext ctx);

	/** {@code x}/{@code y} are the already-resolved (anchor + offset) top-left in unscaled pixels. */
	public abstract void render(Renderer2D renderer, Font font, int x, int y, HudContext ctx);
}

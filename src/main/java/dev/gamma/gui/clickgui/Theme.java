package dev.gamma.gui.clickgui;

import dev.gamma.util.ColorUtil;

/**
 * The single accent color driving the whole ClickGUI/HUD, plus derived hover/active shades and
 * the fixed neutral palette structural chrome is drawn in. A single user-configurable accent drives the whole
 * theme, with hover/active shades derived rather than hardcoded — only {@link #accent} is stored; everything else is a function of it.
 */
public final class Theme {

	/** The salmon the client ships with. Changed from the original blue-violet on request. */
	public static final int DEFAULT_ACCENT = ColorUtil.argb(255, 243, 111, 111);

	private int accent;

	/**
	 * The one live theme, for the handful of places that draw with the client accent but are not
	 * handed a {@code Theme} -- the Spotify HUD overlay in particular. Set by the constructor, the
	 * same pattern the modules use for their singletons.
	 */
	public static volatile Theme instance;

	public Theme(int accent) {
		this.accent = accent;
		instance = this;
	}

	public Theme() {
		this(DEFAULT_ACCENT);
	}

	public int accent() {
		return accent;
	}

	public void setAccent(int accent) {
		this.accent = accent;
	}

	public int accentHover() {
		return ColorUtil.lighten(accent, 0.18);
	}

	public int accentActive() {
		return ColorUtil.darken(accent, 0.15);
	}

	public int accentMuted(double alpha) {
		return ColorUtil.scaleAlpha(accent, alpha);
	}

	// -- structural chrome — a dark neutral base the accent draws attention against --
	//
	// Deliberately low internal contrast: one dark surface, a header the same colour as the body and
	// separated only by a hairline, and rows with no plate at all until hovered. Fewer edges, so the
	// ones that remain carry more.

	public int panelBackground() {
		return ColorUtil.argb(228, 17, 18, 22);
	}

	public int panelHeaderBackground() {
		return ColorUtil.argb(236, 17, 18, 22);
	}

	public int rowBackground() {
		return ColorUtil.argb(0, 32, 33, 38);
	}

	public int rowHoverBackground() {
		return ColorUtil.argb(150, 38, 40, 48);
	}

	public int settingsBackground() {
		return ColorUtil.argb(120, 12, 13, 17);
	}

	/** Hairline separator between the header and the content, and between list rows. */
	public int divider() {
		return ColorUtil.argb(40, 255, 255, 255);
	}

	/**
	 * Fully opaque, unlike everything else here. Dropdowns float over other controls, so any
	 * transparency at all means reading two overlapping sets of text at once — which is exactly how
	 * the block-search suggestions became unusable.
	 */
	public int dropdownBackground() {
		return ColorUtil.argb(255, 26, 27, 32);
	}

	public int shadow() {
		return ColorUtil.argb(70, 0, 0, 0);
	}

	public int textPrimary() {
		return 0xFFF2F2F6;
	}

	public int textSecondary() {
		return 0xFF7E7F8A;
	}

	public int trackOff() {
		return ColorUtil.argb(255, 48, 50, 58);
	}
}

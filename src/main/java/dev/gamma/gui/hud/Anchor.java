package dev.gamma.gui.hud;

/** The 9 screen anchor points a {@link HudComponent} can snap to; its offset is measured from this point. */
public enum Anchor {
	TOP_LEFT, TOP_CENTER, TOP_RIGHT,
	MIDDLE_LEFT, MIDDLE_CENTER, MIDDLE_RIGHT,
	BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT;

	public int resolveX(int screenWidth, int elementWidth, double offsetX) {
		return switch (this) {
			case TOP_LEFT, MIDDLE_LEFT, BOTTOM_LEFT -> (int) Math.round(offsetX);
			case TOP_CENTER, MIDDLE_CENTER, BOTTOM_CENTER -> (screenWidth - elementWidth) / 2 + (int) Math.round(offsetX);
			case TOP_RIGHT, MIDDLE_RIGHT, BOTTOM_RIGHT -> screenWidth - elementWidth - (int) Math.round(offsetX);
		};
	}

	public int resolveY(int screenHeight, int elementHeight, double offsetY) {
		return switch (this) {
			case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> (int) Math.round(offsetY);
			case MIDDLE_LEFT, MIDDLE_CENTER, MIDDLE_RIGHT -> (screenHeight - elementHeight) / 2 + (int) Math.round(offsetY);
			case BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT -> screenHeight - elementHeight - (int) Math.round(offsetY);
		};
	}

	public boolean isLeftHalf() {
		return this == TOP_LEFT || this == MIDDLE_LEFT || this == BOTTOM_LEFT;
	}

	public boolean isRightHalf() {
		return this == TOP_RIGHT || this == MIDDLE_RIGHT || this == BOTTOM_RIGHT;
	}

	public boolean isTopHalf() {
		return this == TOP_LEFT || this == TOP_CENTER || this == TOP_RIGHT;
	}

	public boolean isBottomHalf() {
		return this == BOTTOM_LEFT || this == BOTTOM_CENTER || this == BOTTOM_RIGHT;
	}
}

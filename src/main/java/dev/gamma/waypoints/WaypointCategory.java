package dev.gamma.waypoints;

import dev.gamma.util.ColorUtil;

/** Fixed set of waypoint categories — each carries a default icon and color, both overridable per-{@link Waypoint}. */
public enum WaypointCategory {

	HOME(WaypointIcon.BEACON, ColorUtil.argb(255, 90, 200, 255)),
	BASE(WaypointIcon.SQUARE, ColorUtil.argb(255, 60, 220, 120)),
	STASH(WaypointIcon.DIAMOND, ColorUtil.argb(255, 230, 200, 60)),
	MINE(WaypointIcon.TRIANGLE, ColorUtil.argb(255, 160, 110, 70)),
	FARM(WaypointIcon.CROSS, ColorUtil.argb(255, 130, 220, 70)),
	NETHER_PORTAL(WaypointIcon.CIRCLE, ColorUtil.argb(255, 220, 60, 90)),
	END_PORTAL(WaypointIcon.CIRCLE, ColorUtil.argb(255, 170, 60, 220)),
	LOGOUT(WaypointIcon.CIRCLE, ColorUtil.argb(255, 150, 150, 160)),
	DEATH(WaypointIcon.SKULL, ColorUtil.argb(255, 210, 50, 50)),
	MISC(WaypointIcon.CIRCLE, ColorUtil.argb(255, 200, 200, 200));

	private final WaypointIcon icon;
	private final int defaultColor;

	WaypointCategory(WaypointIcon icon, int defaultColor) {
		this.icon = icon;
		this.defaultColor = defaultColor;
	}

	public WaypointIcon icon() {
		return icon;
	}

	public int defaultColor() {
		return defaultColor;
	}
}

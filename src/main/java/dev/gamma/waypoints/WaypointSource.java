package dev.gamma.waypoints;

/** How a {@link Waypoint} came to exist — purely informational (shown in {@code .waypoints list}), never affects rendering. */
public enum WaypointSource {
	COMMAND,
	MAP,
	STASH_AUTO,
	LOGOUT_AUTO,
	IMPORT
}

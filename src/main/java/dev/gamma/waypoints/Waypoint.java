package dev.gamma.waypoints;

import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * A stored waypoint. Immutable (rebuilt-on-mutation via {@link WaypointStore}, same shape as
 * {@code modules.esp.LogoutSpots}' own record-based spots) — there's no edit command in this
 * phase, only create/remove, so mutability would buy nothing.
 *
 * @param dimension     e.g. {@code "minecraft:overworld"} — matches {@code ChunkObservation}'s convention
 * @param colorOverride {@code null} to use {@link WaypointCategory#defaultColor()}
 * @param iconOverride  {@code null} to use {@link WaypointCategory#icon()}
 */
public record Waypoint(
		UUID id,
		String name,
		WaypointCategory category,
		String dimension,
		double x,
		double y,
		double z,
		Integer colorOverride,
		WaypointIcon iconOverride,
		boolean beaconBeam,
		boolean visible,
		long createdAtMillis,
		WaypointSource source
) {
	public int color() {
		return colorOverride != null ? colorOverride : category.defaultColor();
	}

	public WaypointIcon icon() {
		return iconOverride != null ? iconOverride : category.icon();
	}

	public Vec3 position() {
		return new Vec3(x, y, z);
	}

	public double distanceTo(double px, double py, double pz) {
		double dx = x - px;
		double dy = y - py;
		double dz = z - pz;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}

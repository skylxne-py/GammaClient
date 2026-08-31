package dev.gamma.waypoints;

import java.util.Optional;

/** The Overworld/Nether 8:1 coordinate conversion — same math as {@code gui.hud.elements.CoordinatesElement}, reused here for the "show cross-dimension equivalent" waypoint toggle. */
public final class DimensionConversion {

	public static final String OVERWORLD = "minecraft:overworld";
	public static final String NETHER = "minecraft:the_nether";

	private DimensionConversion() {
	}

	/** Where {@code (x, z)} in {@code fromDimension} would land in {@code toDimension}, if a conversion between the two is defined (Overworld <-> Nether only — the End has no fixed ratio). */
	public static Optional<Point> convert(String fromDimension, double x, double z, String toDimension) {
		if (fromDimension.equals(toDimension)) {
			return Optional.empty();
		}
		if (OVERWORLD.equals(fromDimension) && NETHER.equals(toDimension)) {
			return Optional.of(new Point(x / 8.0, z / 8.0));
		}
		if (NETHER.equals(fromDimension) && OVERWORLD.equals(toDimension)) {
			return Optional.of(new Point(x * 8.0, z * 8.0));
		}
		return Optional.empty();
	}

	public record Point(double x, double z) {
	}
}

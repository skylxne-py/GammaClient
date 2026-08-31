package dev.gamma.render;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Frustum visibility and distance-limit checks, meant to be applied while a module builds
 * its draw list during extraction — a culled shape should never reach {@link Renderer3D} in
 * the first place, per project convention ("Frustum culling and a distance limit applied at
 * extraction, not render").
 */
public final class Culling {

	private Culling() {
	}

	public static boolean isVisible(Frustum frustum, AABB box) {
		return frustum.isVisible(box);
	}

	public static boolean withinDistance(Vec3 from, Vec3 to, double maxDistance) {
		return from.distanceToSqr(to) <= maxDistance * maxDistance;
	}

	/**
	 * Raw-coordinate overload, for extraction loops that would otherwise allocate a {@link Vec3}
	 * per candidate purely to throw most of them away. Same test, no garbage.
	 */
	public static boolean withinDistance(Vec3 from, double x, double y, double z, double maxDistance) {
		double dx = from.x - x;
		double dy = from.y - y;
		double dz = from.z - z;
		return dx * dx + dy * dy + dz * dz <= maxDistance * maxDistance;
	}

	/**
	 * Y ceiling shared by every ESP's {@code MaxHeight} setting, so the number means the same
	 * thing everywhere: show this Y and everything below it. The point is cutting surface noise
	 * when hunting underground — at the default the whole column is visible.
	 */
	public static final int HEIGHT_LIMIT_OFF = 320;

	/**
	 * {@link #HEIGHT_LIMIT_OFF} is treated as "no limit" rather than a literal Y of 320, so a
	 * datapack dimension built above the vanilla ceiling isn't silently clipped by a setting the
	 * user never touched.
	 */
	public static boolean belowHeight(double y, int maxHeight) {
		return maxHeight >= HEIGHT_LIMIT_OFF || y <= maxHeight;
	}

	/** Combined distance + frustum check, in the cheap-to-expensive order that's fastest to fail. */
	public static boolean shouldRender(Frustum frustum, AABB box, Vec3 from, double maxDistance) {
		return withinDistance(from, box.getCenter(), maxDistance) && isVisible(frustum, box);
	}
}

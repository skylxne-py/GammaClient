package dev.gamma.render;

import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Builds the AABBs/points {@link Renderer3D} draws — common 3D primitives, no draw calls. */
public final class ShapeBuilder {

	private ShapeBuilder() {
	}

	/** The entity's bounding box, repositioned to its interpolated (render-tick) position. */
	public static AABB entityBox(Entity entity, float partialTick) {
		Vec3 interpolated = entity.getPosition(partialTick);
		Vec3 delta = interpolated.subtract(entity.position());
		return entity.getBoundingBox().move(delta);
	}

	/** Full-block outline. */
	public static AABB blockOutline(BlockPos pos) {
		return new AABB(pos);
	}

	/** Outline matching the block's actual collision/selection shape, not the full cube. */
	public static AABB blockOutline(BlockPos pos, VoxelShape shape) {
		return shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
	}

	/** The chunk's XZ column between minY and maxY (inclusive-exclusive, world height range). */
	public static AABB chunkBoundary(ChunkPos pos, int minY, int maxY) {
		return new AABB(
				pos.getMinBlockX(), minY, pos.getMinBlockZ(),
				pos.getMinBlockX() + 16, maxY, pos.getMinBlockZ() + 16);
	}

	// Nudges the origin this far along the camera's own forward vector, not each tracer's -- a
	// point exactly at the eye clips against the 0.05-block near plane along whatever direction
	// the tracer itself points, so every tracer appeared to start from a different, scattered
	// spot instead of the crosshair.
	private static final float TRACER_ORIGIN_FORWARD_OFFSET = 2.0f;

	/**
	 * Where a {@link Renderer3D#tracer} should start: the world point that lands dead centre on
	 * screen, i.e. under the crosshair.
	 *
	 * <p>A fixed offset straight ahead of the camera is only centred if the world is drawn with
	 * the camera's own matrices, and it isn't: {@code GameRenderer.renderLevel} folds a bob pose
	 * in on top of them (see {@link ViewBobTransform}). Picking the origin means solving for the
	 * point that ends up centred *after* that, so the full chain is inverted here — take the
	 * straight-ahead direction into view space, undo the bob, and come back out to world space.
	 * With no bob active the two inverse steps cancel and this is exactly
	 * {@code cameraPos + forward * offset}, the naive formula.
	 */
	public static Vec3 tracerOrigin(Camera camera) {
		Vector3fc forward = camera.forwardVector();
		Matrix4f bob = ViewBobTransform.current();
		if (bob == null) {
			return camera.position().add(
					forward.x() * TRACER_ORIGIN_FORWARD_OFFSET,
					forward.y() * TRACER_ORIGIN_FORWARD_OFFSET,
					forward.z() * TRACER_ORIGIN_FORWARD_OFFSET);
		}

		// Straight ahead of the camera, expressed in view space. Derived from the camera rather
		// than hardcoded as -Z so this holds whatever handedness vanilla's view space uses.
		Matrix4f viewRotation = camera.getViewRotationMatrix(new Matrix4f());
		Vector3f origin = viewRotation.transformDirection(new Vector3f(forward)).mul(TRACER_ORIGIN_FORWARD_OFFSET);
		bob.invertAffine().transformPosition(origin);
		viewRotation.invert().transformPosition(origin);
		return camera.position().add(origin.x(), origin.y(), origin.z());
	}
}

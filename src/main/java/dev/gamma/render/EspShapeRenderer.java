package dev.gamma.render;

import dev.gamma.util.ColorUtil;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.AABB;

/**
 * Draws an {@link AABB} in one of the styles ESP modules (EntityESP, PlayerESP, Chams, ...) all
 * need: a box, a tracer line from the camera, or both. Shared here so every module's Mode
 * setting means the same thing, instead of each hand-rolling its own box/tracer toggle.
 *
 * <p>{@code inFrustum} only gates the box -- there's no point drawing a box for something off
 * screen, but a tracer's entire purpose is pointing at something that isn't in view, so it draws
 * regardless. Callers frustum-cull before calling this (see {@link Culling}) since that decision
 * needs the caller's own frustum/camera-position context; this only applies the result.
 */
public final class EspShapeRenderer {

	private static final int BOX_FILL_ALPHA = 40;

	private EspShapeRenderer() {
	}

	public static void draw(AABB box, int color, RenderPass pass, EspMode mode, Camera camera, boolean inFrustum) {
		if (inFrustum && (mode == EspMode.BOX || mode == EspMode.BOTH)) {
			Renderer3D.outlinedBox(box, color, ColorUtil.withAlpha(color, BOX_FILL_ALPHA), pass);
		}
		if (mode == EspMode.TRACER || mode == EspMode.BOTH) {
			Renderer3D.tracer(ShapeBuilder.tracerOrigin(camera), box.getCenter(), color, pass);
		}
	}
}

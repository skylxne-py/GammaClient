package dev.gamma.render;

/**
 * Depth-test behavior for a {@link Renderer3D} draw. THROUGH_WALLS maps to vanilla's
 * {@code GizmoProperties.setAlwaysOnTop()} — declared gizmo pipeline state, not a runtime
 * GL toggle. BOTH draws a full-strength depth-tested copy plus a dimmed always-on-top copy,
 * the common "solid up close, faint through walls" ESP look.
 */
public enum RenderPass {
	DEPTH_TESTED,
	THROUGH_WALLS,
	BOTH
}

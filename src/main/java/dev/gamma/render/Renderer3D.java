package dev.gamma.render;

import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * World-space drawing, per the project's Renderer3D API. Every method is a facade over
 * vanilla's {@code net.minecraft.gizmos.Gizmos} — see the design notes (2026-07-28)
 * for why: {@code net.minecraft.client.renderer.rendertype.RenderType} construction is
 * package-private in 26.2, so a mod cannot submit its own {@code RenderPipeline} into world
 * space without a full custom {@code FeatureRendererType}/{@code FeatureRenderer}. Gizmos is
 * public, unconditional, and already batches depth-tested vs. through-walls exactly as this
 * API needs.
 *
 * <p>Call these only from a {@code WorldRenderExtractEvent} handler — that is the scope in
 * which vanilla's per-frame gizmo collector is open (traced through {@code Minecraft.
 * renderFrame}). Calling from anywhere else silently no-ops (Gizmos falls back to a NOOP
 * collector off-scope) rather than drawing nothing loudly, so this is a hard contract, not
 * a suggestion.
 */
public final class Renderer3D {

	private static final float DEFAULT_LINE_WIDTH = 2.0f;
	private static final float THROUGH_WALLS_ALPHA_SCALE = 0.4f;

	private Renderer3D() {
	}

	public static void box(AABB box, int color, RenderPass pass) {
		box(box, color, DEFAULT_LINE_WIDTH, pass);
	}

	public static void box(AABB box, int color, float lineWidth, RenderPass pass) {
		submit(pass, GizmoStyle.stroke(color, lineWidth), style -> Gizmos.cuboid(box, style));
	}

	public static void filledBox(AABB box, int fillColor, RenderPass pass) {
		submit(pass, GizmoStyle.fill(fillColor), style -> Gizmos.cuboid(box, style));
	}

	public static void outlinedBox(AABB box, int strokeColor, int fillColor, RenderPass pass) {
		outlinedBox(box, strokeColor, fillColor, DEFAULT_LINE_WIDTH, pass);
	}

	public static void outlinedBox(AABB box, int strokeColor, int fillColor, float lineWidth, RenderPass pass) {
		submit(pass, GizmoStyle.strokeAndFill(strokeColor, lineWidth, fillColor), style -> Gizmos.cuboid(box, style));
	}

	public static void line(Vec3 start, Vec3 end, int color, RenderPass pass) {
		submit(pass, color, c -> Gizmos.line(start, end, c));
	}

	public static void line(Vec3 start, Vec3 end, int color, float width, RenderPass pass) {
		submit(pass, color, c -> Gizmos.line(start, end, c, width));
	}

	/** Origin is typically {@link ShapeBuilder#tracerOrigin} (the camera's eye position). */
	public static void tracer(Vec3 origin, Vec3 target, int color, RenderPass pass) {
		line(origin, target, color, pass);
	}

	public static void tracer(Vec3 origin, Vec3 target, int color, float width, RenderPass pass) {
		line(origin, target, color, width, pass);
	}

	public static void quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color, RenderPass pass) {
		submit(pass, GizmoStyle.fill(color), style -> Gizmos.rect(a, b, c, d, style));
	}

	public static void text3d(Vec3 pos, String text, int color, RenderPass pass) {
		text3d(pos, text, color, TextGizmo.Style.DEFAULT_SCALE, pass);
	}

	/**
	 * A billboard label centred on {@code pos}.
	 *
	 * <p>{@code TextGizmo.Style.forColor} is <em>not</em> what a floating label wants, despite the
	 * name: it sets {@code adjustLeft} to 0, which anchors the text's left edge at the point, so a
	 * label hangs off to the right of whatever it is labelling by half its own width — visibly
	 * off-centre above a player's head, and worse the longer the text. {@code forColorAndCentered}
	 * leaves {@code adjustLeft} empty, which is the centred case. Nothing in this codebase draws
	 * left-aligned text in world space, so this is centred outright rather than offered as a
	 * variant.
	 */
	public static void text3d(Vec3 pos, String text, int color, float scale, RenderPass pass) {
		submit(pass, color, c -> Gizmos.billboardText(text, pos, TextGizmo.Style.forColorAndCentered(c).withScale(scale)));
	}

	@FunctionalInterface
	private interface StyledShape {
		GizmoProperties draw(GizmoStyle style);
	}

	@FunctionalInterface
	private interface ColoredShape {
		GizmoProperties draw(int color);
	}

	private static void submit(RenderPass pass, GizmoStyle style, StyledShape shape) {
		switch (pass) {
			case DEPTH_TESTED -> shape.draw(style);
			case THROUGH_WALLS -> shape.draw(style).setAlwaysOnTop();
			case BOTH -> {
				shape.draw(style);
				shape.draw(dim(style)).setAlwaysOnTop();
			}
		}
	}

	private static void submit(RenderPass pass, int color, ColoredShape shape) {
		switch (pass) {
			case DEPTH_TESTED -> shape.draw(color);
			case THROUGH_WALLS -> shape.draw(color).setAlwaysOnTop();
			case BOTH -> {
				shape.draw(color);
				shape.draw(dimAlpha(color)).setAlwaysOnTop();
			}
		}
	}

	private static GizmoStyle dim(GizmoStyle style) {
		return new GizmoStyle(dimAlpha(style.stroke()), style.strokeWidth(), dimAlpha(style.fill()));
	}

	private static int dimAlpha(int argb) {
		int alpha = (argb >>> 24) & 0xFF;
		int dimmed = Math.round(alpha * THROUGH_WALLS_ALPHA_SCALE);
		return (dimmed << 24) | (argb & 0x00FFFFFF);
	}
}

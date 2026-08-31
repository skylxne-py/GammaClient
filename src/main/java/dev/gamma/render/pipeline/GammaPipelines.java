package dev.gamma.render.pipeline;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

/**
 * Gamma's own declared {@link RenderPipeline} instances — static, built once here, never
 * mutated per-frame. Used only by {@code Renderer2D}: {@code GuiGraphicsExtractor.fill}/
 * {@code .blit} accept a raw pipeline directly, which is the one place in 26.2's Blaze3D a
 * mod can safely draw with its own pipeline. World space cannot — {@code RenderType}
 * construction is package-private there, so {@code Renderer3D} is a facade over vanilla's
 * {@code net.minecraft.gizmos.Gizmos} instead. See the design notes (2026-07-28).
 *
 * <p>Shaders are vanilla's own {@code core/gui} / {@code core/position_tex_color} source (bare
 * strings default to the {@code minecraft} namespace) — reused as-is, only the blend/cull/
 * topology pipeline state differs per variant. No custom GLSL is authored here.
 *
 * <p>{@code BindGroupLayouts.GLOBALS} and {@code .MATRICES_PROJECTION} (plus {@code .SAMPLER0}
 * for the textured variant) are mandatory, not optional polish — verified against vanilla's own
 * {@code RenderPipelines.GUI}/{@code GUI_TEXTURED}, which compose them from {@code GLOBALS_SNIPPET}
 * and {@code GUI_SNIPPET}/{@code GUI_TEXTURED_SNIPPET}. Without {@code MATRICES_PROJECTION}
 * specifically, the GUI vertex shader has no projection matrix bound, so every fill/rect/circle
 * transforms to nothing visible — everything still "renders" (no error, no crash), it just never
 * lands on screen. An earlier version of this file omitted both bind groups and used
 * {@code core/position_color} instead of {@code core/gui} for the solid variants; text still drew
 * (a separate, correctly-configured vanilla pipeline) while every custom-pipeline draw call was
 * silently invisible — first fixed here, since it was mistaken for a per-widget rendering bug.
 */
public final class GammaPipelines {

	/** Flat-colored, alpha-blended fills — panel backgrounds, rasterized rounded-rect strips. */
	public static final RenderPipeline GUI_SOLID = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("gamma", "pipeline/gui_solid"))
			.withBindGroupLayout(BindGroupLayouts.GLOBALS)
			.withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
			.withVertexShader("core/gui")
			.withFragmentShader("core/gui")
			.withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.withPolygonMode(PolygonMode.FILL)
			.withCull(false)
			.build();

	/** Same shape as {@link #GUI_SOLID} but with blending off — fully opaque backgrounds. */
	public static final RenderPipeline GUI_SOLID_OPAQUE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("gamma", "pipeline/gui_solid_opaque"))
			.withBindGroupLayout(BindGroupLayouts.GLOBALS)
			.withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
			.withVertexShader("core/gui")
			.withFragmentShader("core/gui")
			.withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withColorTargetState(ColorTargetState.DEFAULT)
			.withPolygonMode(PolygonMode.FILL)
			.withCull(false)
			.build();

	/** Tinted, alpha-blended texture blits — icons and atlas sprites. */
	public static final RenderPipeline GUI_TEXTURED = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("gamma", "pipeline/gui_textured"))
			.withBindGroupLayout(BindGroupLayouts.GLOBALS)
			.withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
			.withVertexShader("core/position_tex_color")
			.withFragmentShader("core/position_tex_color")
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.withPolygonMode(PolygonMode.FILL)
			.withCull(false)
			.build();

	private GammaPipelines() {
	}
}

package dev.gamma.mixin.render;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Stops Gamma's through-walls shapes being tinted by world fog. ESP boxes went black at distance
 * and blue underwater, which is exactly what fog does and exactly what an overlay must not do.
 *
 * <p>Why fog reaches them at all: {@code Renderer3D} draws via {@code Gizmos}, and gizmo geometry
 * goes through {@code RenderPipelines.LINES} / {@code DEBUG_FILLED_BOX}. Both are built from
 * {@code MATRICES_FOG_SNIPPET} and run the {@code core/rendertype_lines} shader, so they sample
 * the fog UBO like any piece of terrain. The pipelines are vanilla's and are not ours to change
 * (see the project conventions — no raw GL, no custom world-space {@code RenderType}), so the fix has to be the
 * buffer that gets bound, not the shader that reads it.
 *
 * <p>{@code LevelRenderer} sets the fog buffer once per pass — sky, main, weather, always-on-top —
 * and everything {@code setAlwaysOnTop()} draws lands in that last one. Swapping only that pass's
 * buffer for {@code FogMode.NONE} therefore un-fogs every {@code RenderPass.THROUGH_WALLS} shape
 * and touches nothing else: terrain, sky and weather all keep the fog they were given.
 *
 * <p>No Fabric event covers this, and no module owns it — an overlay being legible is a property
 * of the render layer itself, not a feature to be toggled. {@code RenderPass.DEPTH_TESTED} shapes
 * are deliberately left alone: they draw in the main pass alongside terrain, where matching the
 * world's fog is the point. Kill fog outright with {@code NoRender}'s Fog toggle if that is what
 * you want.
 *
 * <p>Vanilla's own always-on-top gizmos (the F3 debug renderers) lose their fog too. They are
 * debug overlays with the same legibility argument, so that is a fix rather than a regression.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererGizmoFogMixin {

	/**
	 * {@code argsOnly} with no index: {@code addAlwaysOnTopPass} takes exactly one
	 * {@code GpuBufferSlice}, so the type alone identifies it and this doesn't silently retarget
	 * if the parameter order changes.
	 */
	@ModifyVariable(method = "addAlwaysOnTopPass", at = @At("HEAD"), argsOnly = true)
	private GpuBufferSlice gamma$unfogAlwaysOnTopPass(GpuBufferSlice fogBuffer) {
		GameRenderer gameRenderer = Minecraft.getInstance().gameRenderer;
		if (gameRenderer == null) {
			return fogBuffer;
		}
		FogRenderer fogRenderer = ((GameRendererFogAccessor) gameRenderer).gamma$fogRenderer();
		return fogRenderer == null ? fogBuffer : fogRenderer.getBuffer(FogRenderer.FogMode.NONE);
	}
}

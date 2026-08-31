package dev.gamma.mixin.render;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code GameRenderer.fogRenderer} is private with no getter, and it owns the only fog-free
 * {@code GpuBufferSlice} in the game — {@code FogRenderer.getBuffer(FogMode.NONE)} hands back an
 * instance-owned buffer pre-filled with a transparent colour and every fog distance at
 * {@code Float.MAX_VALUE}. {@link LevelRendererGizmoFogMixin} needs that exact buffer, and
 * building an equivalent one by hand would mean duplicating vanilla's UBO layout and keeping it
 * in sync forever. Exposing the field is the smaller commitment.
 */
@Mixin(GameRenderer.class)
public interface GameRendererFogAccessor {

	@Accessor("fogRenderer")
	FogRenderer gamma$fogRenderer();
}

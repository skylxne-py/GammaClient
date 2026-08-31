package dev.gamma.mixin.render;

import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code FogRenderer.fogEnabled} is a private static field with no public getter/setter — the
 * only way to read and flip it (for {@code NoRender}'s fog toggle) without duplicating vanilla's
 * own fog-disable state is to expose it directly.
 */
@Mixin(FogRenderer.class)
public interface FogRendererAccessor {

	@Accessor("fogEnabled")
	static boolean gamma$isFogEnabled() {
		throw new AssertionError("Mixin not applied");
	}

	@Accessor("fogEnabled")
	static void gamma$setFogEnabled(boolean enabled) {
		throw new AssertionError("Mixin not applied");
	}
}

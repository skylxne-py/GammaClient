package dev.gamma.mixin.render;

import dev.gamma.modules.render.NoRender;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code NoRender}'s weather toggles — suppresses the falling rain/snow column pass, never the
 * server-side weather state.
 *
 * <h2>Two injections, because rain and snow separate before they are drawn, not while</h2>
 *
 * <p>{@code render} draws both types in one pass, so cancelling it is all-or-nothing and is only
 * right when both are switched off. The separation lives one step earlier:
 * {@link WeatherRenderState} carries {@code rainColumns} and {@code snowColumns} as two public
 * lists, filled by {@code extractRenderState}. Emptying one of them after extraction leaves the
 * renderer with nothing of that type to draw and needs no knowledge of how it draws it.
 *
 * <p>Clearing the list is safe because the state is per-frame scratch: it has a {@code reset()} and
 * is refilled by the next extraction, so nothing is being destroyed that is not about to be rebuilt.
 * This also keeps the modification on the extraction side of the extract/render split rather than
 * mutating state mid-render, which is the pattern the project conventions asks for everywhere else.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {

	/** The cheap path: nothing falls at all, so the whole pass and its GPU setup are skipped. */
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void gamma$render(Vec3 pos, WeatherRenderState state, CallbackInfo ci) {
		NoRender noRender = NoRender.instance;
		if (noRender != null && !noRender.weatherEnabled()) {
			ci.cancel();
		}
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void gamma$extractRenderState(ClientLevel level, float partialTick, Vec3 pos, WeatherRenderState state, CallbackInfo ci) {
		NoRender noRender = NoRender.instance;
		if (noRender == null) {
			return;
		}
		if (!noRender.rainEnabled()) {
			state.rainColumns.clear();
		}
		if (!noRender.snowEnabled()) {
			state.snowColumns.clear();
		}
	}
}

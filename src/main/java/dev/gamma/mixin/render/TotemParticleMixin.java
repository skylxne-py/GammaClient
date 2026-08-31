package dev.gamma.mixin.render;

import dev.gamma.modules.render.NoRender;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TotemParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code NoRender}'s totem-animation toggle. {@code TotemParticle$Provider.createParticle} is
 * overloaded (a typed version plus its erasure bridge), which Mixin can only target by exact
 * bytecode descriptor — too fragile to hand-write and unverifiable until an actual game launch.
 * {@code TotemParticle} has exactly one constructor, so marking the particle removed right after
 * construction is the safer, unambiguous target; a removed particle is never added to the active
 * render list. {@code remove()} is declared on {@code Particle}, not on {@code TotemParticle}
 * itself, and {@code @Shadow} only resolves members declared directly in the mixin's target
 * class — so this calls it via a direct cast instead, same as {@code CameraMixin}.
 */
@Mixin(TotemParticle.class)
public abstract class TotemParticleMixin {

	@Inject(method = "<init>", at = @At("RETURN"))
	private void gamma$suppress(ClientLevel level, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed, SpriteSet sprites, CallbackInfo ci) {
		NoRender noRender = NoRender.instance;
		if (noRender != null && !noRender.totemAnimationEnabled()) {
			((Particle) (Object) this).remove();
		}
	}
}

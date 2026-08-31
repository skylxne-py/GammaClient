package dev.gamma.mixin.render;

import dev.gamma.modules.render.Freecam;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blanks movement input at its source while Freecam is active. No Fabric API event exposes the
 * per-tick input state for modification, and this is the one method that produces it.
 *
 * <p>{@link LocalPlayerMixin} already cancels {@code LocalPlayer.applyInput()}, which was assumed to
 * be enough. It is not: {@code applyInput} only converts the move vector into {@code xxa}/{@code
 * zza} plus {@code jumping}. Everything else reads {@code input.keyPresses} directly and earlier in
 * the same tick —
 *
 * <ul>
 * <li>{@code LocalPlayer.tick()} sets the {@code crouching} field straight from
 *     {@code keyPresses.shift()}, which is why holding shift to descend in Freecam visibly crouched
 *     the real player;</li>
 * <li>sprint start/stop reads {@code input.hasForwardImpulse()} and {@code keyPresses.sprint()};</li>
 * <li>{@code rideTick()} steers a vehicle from the same input, so Freecamming while in a boat or on
 *     a horse moved the real player regardless of {@code applyInput};</li>
 * <li>{@code lastSentInput} goes to the server in {@code ServerboundPlayerInputPacket}, so the
 *     server was told you were holding those keys either way.</li>
 * </ul>
 *
 * <p>Zeroing {@code keyPresses} and {@code moveVector} here, at the tail of the method that just
 * built them, fixes all four at once and leaves exactly one place that knows about the exception.
 * Look direction is untouched — {@code MouseHandlerMixin} already routes that to Freecam — and so
 * are attack/use, which vanilla handles through {@code KeyMapping} entirely separately, so breaking
 * blocks and eating still work.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {

	@Inject(method = "tick", at = @At("TAIL"))
	private void gamma$blankInputDuringFreecam(CallbackInfo ci) {
		Freecam freecam = Freecam.instance;
		if (freecam == null || !freecam.isEnabled()) {
			return;
		}
		this.keyPresses = Input.EMPTY;
		this.moveVector = Vec2.ZERO;
	}
}

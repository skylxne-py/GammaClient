package dev.gamma.mixin.misc;

import dev.gamma.modules.misc.NameProtect;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@code NameProtect} — only ever overrides the local client player's own name, never another entity's. */
@Mixin(Player.class)
public abstract class PlayerNameMixin {

	@Inject(method = "getName", at = @At("RETURN"), cancellable = true)
	private void gamma$getName(CallbackInfoReturnable<Component> cir) {
		if (isLocalPlayerProtected()) {
			cir.setReturnValue(Component.literal(NameProtect.instance.replacement()));
		}
	}

	@Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
	private void gamma$getDisplayName(CallbackInfoReturnable<Component> cir) {
		if (isLocalPlayerProtected()) {
			cir.setReturnValue(Component.literal(NameProtect.instance.replacement()));
		}
	}

	private boolean isLocalPlayerProtected() {
		NameProtect nameProtect = NameProtect.instance;
		return nameProtect != null && nameProtect.isEnabled() && (Object) this == Minecraft.getInstance().player;
	}
}

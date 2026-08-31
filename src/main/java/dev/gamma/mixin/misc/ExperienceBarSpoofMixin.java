package dev.gamma.mixin.misc;

import dev.gamma.modules.donutsmp.FakeInventory;
import net.minecraft.client.gui.contextualbar.ExperienceBar;
import net.minecraft.client.player.LocalPlayer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The fill of the experience bar. The level number next to it is spoofed in {@link HudSpoofMixin},
 * because vanilla draws the two from different classes; both are gated on the same
 * {@code ExperienceBar} setting so they always agree.
 *
 * <p>Redirecting the field read leaves {@code getXpNeededForNextLevel} untouched, so the bar is
 * still drawn exactly when vanilla would draw it — a bar that appeared while you had no levels
 * would be a giveaway all of its own.
 */
@Mixin(ExperienceBar.class)
public abstract class ExperienceBarSpoofMixin {

	@Redirect(
			method = "extractBackground",
			at = @At(value = "FIELD", target = "Lnet/minecraft/client/player/LocalPlayer;experienceProgress:F", opcode = Opcodes.GETFIELD))
	private float gamma$disguiseExperienceProgress(LocalPlayer player) {
		FakeInventory fakeInventory = FakeInventory.instance;
		return fakeInventory != null && fakeInventory.experienceSpoofed()
				? fakeInventory.fakeExperienceProgress(player.experienceLevel, player.experienceProgress)
				: player.experienceProgress;
	}
}

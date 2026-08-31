package dev.gamma.mixin.misc;

import dev.gamma.modules.donutsmp.FakeInventory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Disguises your own inventory slots wherever a container screen draws them — the inventory
 * itself, and the bottom half of every chest, furnace and shulker screen. No Fabric API event
 * exists for slot rendering, so this redirects the one {@code Slot.getItem()} call whose result
 * becomes the drawn stack.
 *
 * <p>{@code ordinal = 0} is doing real work here, not decoration. {@code extractSlot} calls
 * {@code getItem()} three times: once for the stack it draws, and twice more inside vanilla's
 * quick-craft (drag-to-distribute) arithmetic, which also mutates the drag set. Only the first is
 * display; leaving the other two alone keeps dragging behaving exactly as it does with the module
 * off. That is the whole design constraint of this module — never let a disguise reach anything
 * the game reasons about.
 *
 * <p>Slots are filtered to the player's own inventory container. Spoofing a chest's contents would
 * hide nothing about you and make the chest unusable; your inventory travels with you and is what
 * a viewer reads.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScreenSpoofMixin {

	@Redirect(
			method = "extractSlot",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;", ordinal = 0))
	private ItemStack gamma$disguiseSlot(Slot slot) {
		ItemStack real = slot.getItem();
		FakeInventory fakeInventory = FakeInventory.instance;
		if (fakeInventory == null || !fakeInventory.inventorySpoofed()) {
			return real;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || slot.container != player.getInventory()) {
			return real;
		}
		return fakeInventory.disguise(real);
	}
}

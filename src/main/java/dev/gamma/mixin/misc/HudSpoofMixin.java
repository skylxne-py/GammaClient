package dev.gamma.mixin.misc;

import dev.gamma.modules.donutsmp.FakeInventory;
import dev.gamma.modules.misc.NameProtect;
import dev.gamma.modules.misc.ScoreboardEditor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.Objective;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Everything {@code Hud} draws that gives away what you have: hotbar slots, the selected-item
 * name popup, the experience level, and the scoreboard sidebar. There is no Fabric API event for
 * any of them — the HUD extract methods are private and take no interceptable payload — so these
 * are mixins by necessity rather than preference.
 *
 * <p>All of them are display-only substitutions at the last possible moment, after vanilla has read
 * the real state and before it turns it into geometry. Three modules feed them: {@link
 * FakeInventory} (items, experience), {@link ScoreboardEditor} (sidebar), and {@code NameProtect}
 * (your name inside a sidebar row). See {@link FakeInventory} for why the item disguise is a stable
 * per-item permutation rather than per-frame randomness.
 */
@Mixin(Hud.class)
public abstract class HudSpoofMixin {

	@Shadow
	private ItemStack lastToolHighlight;

	/**
	 * The single funnel every hotbar and offhand slot goes through. Hooking here and nowhere else
	 * is what keeps the item in your hand real: the first-person item renderer is a different code
	 * path entirely and never sees this.
	 */
	@ModifyVariable(method = "extractSlot", argsOnly = true, at = @At("HEAD"))
	private ItemStack gamma$disguiseHotbarSlot(ItemStack stack) {
		FakeInventory fakeInventory = FakeInventory.instance;
		return fakeInventory != null && fakeInventory.hotbarSpoofed() ? fakeInventory.disguise(stack) : stack;
	}

	/**
	 * The name that floats above the hotbar when you switch slots. Left alone it would announce
	 * the real item the instant you selected it, which is precisely the moment the hotbar disguise
	 * is supposed to hold. Redirecting the field read rather than the finished component matters:
	 * vanilla measures the text to centre it <em>after</em> building it, so substituting at the
	 * source keeps it centred.
	 */
	@Redirect(
			method = "extractSelectedItemName",
			at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/Hud;lastToolHighlight:Lnet/minecraft/world/item/ItemStack;", opcode = Opcodes.GETFIELD))
	private ItemStack gamma$disguiseSelectedItemName(Hud hud) {
		FakeInventory fakeInventory = FakeInventory.instance;
		return fakeInventory != null && fakeInventory.hotbarSpoofed()
				? fakeInventory.disguise(this.lastToolHighlight)
				: this.lastToolHighlight;
	}

	@ModifyArg(
			method = "extractHotbarAndDecorations",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/contextualbar/ContextualBar;extractExperienceLevel(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;I)V"),
			index = 2)
	private int gamma$disguiseExperienceLevel(int level) {
		FakeInventory fakeInventory = FakeInventory.instance;
		return fakeInventory != null && fakeInventory.experienceSpoofed() ? fakeInventory.fakeExperienceLevel(level) : level;
	}

	/**
	 * Row index within the sidebar, so {@link ScoreboardEditor} can address rows individually.
	 * Vanilla draws them in one sequential loop with no index available at the call site, so it is
	 * counted here and reset at the top of each frame's draw.
	 */
	@Unique
	private int gamma$scoreboardRow;

	@Inject(method = "displayScoreboardSidebar", at = @At("HEAD"))
	private void gamma$resetScoreboardRow(GuiGraphicsExtractor extractor, Objective objective, CallbackInfo ci) {
		gamma$scoreboardRow = 0;
	}

	/** Ordinal 0 is the objective heading, drawn once before the row loop. */
	@ModifyArg(
			method = "displayScoreboardSidebar",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V", ordinal = 0),
			index = 1)
	private Component gamma$editScoreboardTitle(Component title) {
		ScoreboardEditor editor = ScoreboardEditor.instance;
		return editor != null && editor.isEnabled() ? editor.editTitle(title) : title;
	}

	/**
	 * Sidebar row labels (ordinal 1; ordinal 2 is the score column on the right).
	 *
	 * <p>Two modules meet here. {@link ScoreboardEditor} rewrites the row first, so a row you have
	 * explicitly replaced stays replaced. {@code NameProtect} then runs on whatever is left, and it
	 * belongs here rather than in {@link dev.gamma.mixin.misc.PlayerNameMixin} because the sidebar
	 * builds its rows from score-holder strings rather than from the {@code Player} entity — the
	 * {@code getName} override never sees them, so your real name sat in the sidebar in plain sight.
	 */
	@ModifyArg(
			method = "displayScoreboardSidebar",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V", ordinal = 1),
			index = 1)
	private Component gamma$editScoreboardName(Component name) {
		int row = gamma$scoreboardRow++;
		Component edited = name;
		ScoreboardEditor editor = ScoreboardEditor.instance;
		if (editor != null && editor.isEnabled()) {
			edited = editor.editLine(name, row);
		}

		NameProtect nameProtect = NameProtect.instance;
		if (nameProtect == null || !nameProtect.isEnabled()) {
			return edited;
		}
		// The account name, not Player.getName() -- PlayerNameMixin has already replaced that, and
		// asking it here would mean searching the row for the replacement instead of the real name.
		String realName = Minecraft.getInstance().getGameProfile().name();
		String text = edited.getString();
		if (realName == null || realName.isEmpty() || !text.contains(realName)) {
			return edited;
		}
		return Component.literal(text.replace(realName, nameProtect.replacement())).setStyle(edited.getStyle());
	}

	@ModifyArg(
			method = "displayScoreboardSidebar",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V", ordinal = 2),
			index = 1)
	private Component gamma$editScoreboardScore(Component score) {
		ScoreboardEditor editor = ScoreboardEditor.instance;
		return editor != null && editor.isEnabled() ? editor.editScore(score) : score;
	}
}

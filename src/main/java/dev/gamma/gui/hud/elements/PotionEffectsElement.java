package dev.gamma.gui.hud.elements;

import dev.gamma.gui.hud.Anchor;
import dev.gamma.gui.hud.HudComponent;
import dev.gamma.gui.hud.HudContext;
import dev.gamma.render.Renderer2D;
import net.minecraft.client.gui.Font;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.List;

public final class PotionEffectsElement extends HudComponent {

	private static final String[] ROMAN = {"", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
	private static final int LINE_GAP = 2;

	public PotionEffectsElement() {
		super("potion_effects", "Potion Effects", Anchor.MIDDLE_RIGHT, 6, 0);
	}

	private List<MobEffectInstance> effects(HudContext ctx) {
		return ctx.hasPlayer() ? List.copyOf(ctx.player().getActiveEffects()) : List.of();
	}

	@Override
	public int measureWidth(Font font, HudContext ctx) {
		return effects(ctx).stream().mapToInt(effect -> font.width(lineFor(effect))).max().orElse(0);
	}

	@Override
	public int measureHeight(Font font, HudContext ctx) {
		int count = effects(ctx).size();
		return count == 0 ? 0 : count * (font.lineHeight + LINE_GAP) - LINE_GAP;
	}

	@Override
	public void render(Renderer2D renderer, Font font, int x, int y, HudContext ctx) {
		int lineY = y;
		for (MobEffectInstance effect : effects(ctx)) {
			renderer.text(font, lineFor(effect), x, lineY, color());
			lineY += font.lineHeight + LINE_GAP;
		}
	}

	private static String lineFor(MobEffectInstance effect) {
		String name = effect.getEffect().value().getDisplayName().getString();
		String amplifier = effect.getAmplifier() > 0 && effect.getAmplifier() < ROMAN.length ? " " + ROMAN[effect.getAmplifier()] : "";
		String duration = effect.isInfiniteDuration() ? "" : " " + formatTicks(effect.getDuration());
		return name + amplifier + duration;
	}

	private static String formatTicks(int ticks) {
		int totalSeconds = ticks / 20;
		return (totalSeconds / 60) + ":" + String.format("%02d", totalSeconds % 60);
	}
}

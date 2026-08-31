package dev.gamma.gui.hud.elements;

import dev.gamma.gui.hud.Anchor;
import dev.gamma.gui.hud.HudContext;
import dev.gamma.gui.hud.SingleLineHudComponent;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

public final class BiomeElement extends SingleLineHudComponent {

	public BiomeElement() {
		super("biome", "Biome", Anchor.BOTTOM_LEFT, 6, 44);
	}

	@Override
	protected String text(HudContext ctx) {
		if (!ctx.hasPlayer() || ctx.level() == null) {
			return "--";
		}
		Holder<Biome> biome = ctx.level().getBiomeManager().getBiome(ctx.player().blockPosition());
		return biome.unwrapKey().map(key -> key.identifier().getPath()).orElse("unknown");
	}
}

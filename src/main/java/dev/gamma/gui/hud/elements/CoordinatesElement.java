package dev.gamma.gui.hud.elements;

import dev.gamma.gui.hud.Anchor;
import dev.gamma.gui.hud.HudContext;
import dev.gamma.gui.hud.SingleLineHudComponent;
import dev.gamma.modules.misc.FakeCoordinates;
import net.minecraft.world.level.Level;

/** Position, plus the Overworld/Nether equivalent coordinate (the waypoints cross-dimension toggle reuses this ×8/÷8 math later). */
public final class CoordinatesElement extends SingleLineHudComponent {

	public CoordinatesElement() {
		super("coordinates", "Coordinates", Anchor.BOTTOM_LEFT, 6, 20);
	}

	@Override
	protected String text(HudContext ctx) {
		if (!ctx.hasPlayer()) {
			return "--, --, --";
		}
		FakeCoordinates fake = FakeCoordinates.instance;
		if (fake != null && fake.isEnabled()) {
			double[] displayed = fake.displayPosition(ctx.player());
			return String.format("%.3f, %.3f, %.3f", displayed[0], displayed[1], displayed[2]);
		}

		int x = (int) Math.floor(ctx.player().getX());
		int y = (int) Math.floor(ctx.player().getY());
		int z = (int) Math.floor(ctx.player().getZ());
		String base = x + ", " + y + ", " + z;

		if (ctx.level() == null) {
			return base;
		}
		if (ctx.level().dimension() == Level.NETHER) {
			return base + " (OW " + (x * 8) + ", " + (z * 8) + ")";
		}
		if (ctx.level().dimension() == Level.OVERWORLD) {
			return base + " (NR " + (x / 8) + ", " + (z / 8) + ")";
		}
		return base;
	}
}

package dev.gamma.modules.misc;

import dev.gamma.config.setting.BoolSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Client-side display substitution only — the coordinates the server tracks you at, and every
 * gameplay system that depends on them, are completely untouched. This purely changes what
 * {@link dev.gamma.gui.hud.elements.CoordinatesElement} draws on screen, for streaming/screen
 * -sharing without revealing where you actually are. Not a movement or position exploit.
 *
 * <p>A fresh random anchor (large enough X/Z to look like a real far-from-spawn position, with
 * the fractional precision a genuine coordinate readout has) is picked every time the module is
 * enabled, rather than being user-set — a fixed value the user typed in is exactly the kind of
 * suspiciously round, memorable number a real coordinate never is.
 *
 * <p>{@code FollowMovement} keeps the displayed coordinate internally consistent — it tracks
 * your real movement delta from that anchor rather than showing a frozen number, so it still
 * looks like a normal, live coordinate readout instead of a suspiciously static one.
 *
 * <p>{@code RealHeight} (on by default) passes your actual Y through untouched. Height on its own
 * locates nothing — it is X/Z that give a base away — and the real value is the one you actually
 * want to read while mining, so faking it costs you information for no privacy gain. It also
 * makes the readout <em>more</em> plausible, not less: a Y that tracks the terrain you are
 * visibly standing on is exactly what a real coordinate does.
 */
public final class FakeCoordinates extends Module {

	private static final double HORIZONTAL_RANGE = 500_000.0;
	private static final double MIN_Y = 40.0;
	private static final double MAX_Y = 140.0;

	public static volatile FakeCoordinates instance;

	private final BoolSetting followMovement = register(new BoolSetting("FollowMovement", "Move the fake coordinate with your real movement, instead of showing a frozen number.", true));
	private final BoolSetting realHeight = register(new BoolSetting("RealHeight", "Show your true Y. Height alone gives nothing away — only X/Z locate you — and it keeps the readout useful.", true));

	private double fakeX;
	private double fakeY;
	private double fakeZ;
	private double originRealX;
	private double originRealY;
	private double originRealZ;

	public FakeCoordinates() {
		super("FakeCoordinates", "Shows a random fake position on the HUD instead of your real coordinates.", Category.MISC);
		instance = this;
	}

	@Override
	protected void onEnable() {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		fakeX = random.nextDouble(-HORIZONTAL_RANGE, HORIZONTAL_RANGE);
		fakeY = random.nextDouble(MIN_Y, MAX_Y);
		fakeZ = random.nextDouble(-HORIZONTAL_RANGE, HORIZONTAL_RANGE);

		Player player = Minecraft.getInstance().player;
		if (player != null) {
			originRealX = player.getX();
			originRealY = player.getY();
			originRealZ = player.getZ();
		}
	}

	/** Position {@link dev.gamma.gui.hud.elements.CoordinatesElement} should display in place of the real one. */
	public double[] displayPosition(Player realPlayer) {
		if (!followMovement.get()) {
			return new double[] {fakeX, height(realPlayer), fakeZ};
		}
		return new double[] {
				fakeX + (realPlayer.getX() - originRealX),
				height(realPlayer),
				fakeZ + (realPlayer.getZ() - originRealZ)
		};
	}

	/**
	 * A world position written the way the HUD is currently writing positions — or {@code "hidden"}
	 * when it cannot be.
	 *
	 * <p>Notifications name coordinates ({@code SpawnerFinder}, {@code StashFinder}), and a disguised
	 * coordinate readout is worth nothing if a find pops up two seconds later announcing where you
	 * really are. The disguise has to cover every place a position reaches the screen, not just the
	 * one element it was written for.
	 *
	 * <p>With {@code FollowMovement} on the disguise is a plain translation — the displayed position
	 * is the real one plus a constant anchor offset — so the same offset maps any world coordinate
	 * into the same frame. That keeps the notification useful: the coordinates it gives are the ones
	 * you would walk to according to the readout you are looking at.
	 *
	 * <p>With it off there is no such mapping. The displayed position is frozen at the anchor and
	 * bears no fixed relation to the real one, so a translated coordinate would be neither true nor
	 * consistent with the HUD. There is nothing honest to print, so nothing is printed.
	 */
	public static String describe(double x, double y, double z, boolean includeY) {
		FakeCoordinates module = instance;
		if (module == null || !module.isEnabled()) {
			return format(x, y, z, includeY);
		}
		if (!module.followMovement.get()) {
			return "coords hidden";
		}
		double offsetY = module.realHeight.get() ? 0 : module.fakeY - module.originRealY;
		return format(x + module.fakeX - module.originRealX, y + offsetY, z + module.fakeZ - module.originRealZ, includeY);
	}

	private static String format(double x, double y, double z, boolean includeY) {
		return includeY
				? "%.0f, %.0f, %.0f".formatted(x, y, z)
				: "%.0f, %.0f".formatted(x, z);
	}

	/**
	 * Note the real Y is passed through even when {@code FollowMovement} is off: a frozen X/Z is
	 * the point of that setting, but a frozen height while you are visibly climbing a mountain is
	 * the tell it exists to avoid.
	 */
	private double height(Player realPlayer) {
		if (realHeight.get()) {
			return realPlayer.getY();
		}
		return followMovement.get() ? fakeY + (realPlayer.getY() - originRealY) : fakeY;
	}
}

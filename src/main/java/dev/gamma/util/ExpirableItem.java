package dev.gamma.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Reads the self-destruct deadline off server-side "expirable" items (DonutSMP's Shard Pickaxe and
 * anything else using the same key).
 *
 * <p>The deadline lives in Paper's PersistentDataContainer, which serialises into vanilla's
 * {@code minecraft:custom_data} under {@code PublicBukkitValues}:
 *
 * <pre>{@code
 * minecraft:custom_data = {PublicBukkitValues:{
 *     "minecraft:amethystdriller":1787619197344L,
 *     "minecraft:expirable":1787619197344L}}
 * }</pre>
 *
 * <p>The value is an <b>absolute epoch-millis timestamp</b>, not a remaining-time counter —
 * confirmed by dumping the same stack three times over 100 seconds and getting a byte-identical
 * value, while the server's own lore line counted down. That is what makes a client-side countdown
 * possible at all: nothing has to be received to keep it accurate, so it stays smooth between the
 * roughly once-a-minute stack updates the server sends.
 *
 * <p>Keyed on {@code minecraft:expirable} rather than {@code minecraft:amethystdriller}: the two
 * carried the same number on the sampled pickaxe, but the former is the generic key and should cover
 * other expirable items on the same server, where the latter names this one item.
 *
 * <p>Caveat: the comparison is against the local clock. Server and client agreed to within a couple
 * of seconds when sampled, but a machine with a badly-set clock will show a correspondingly wrong
 * countdown. There is no client-visible server clock to calibrate against.
 */
public final class ExpirableItem {

	private static final String BUKKIT_PDC = "PublicBukkitValues";
	private static final String EXPIRABLE_KEY = "minecraft:expirable";

	/** Returned when a stack carries no deadline — epoch 0 is never a plausible expiry. */
	public static final long NO_DEADLINE = 0L;

	private ExpirableItem() {
	}

	/**
	 * Epoch millis at which the stack self-destructs, or {@link #NO_DEADLINE}.
	 *
	 * <p>{@link CustomData#copyTag()} allocates, so call this on tick and cache the result rather
	 * than per frame from a renderer.
	 */
	public static long deadlineMillis(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return NO_DEADLINE;
		}
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		if (custom == null || custom.isEmpty()) {
			return NO_DEADLINE;
		}
		CompoundTag pdc = custom.copyTag().getCompoundOrEmpty(BUKKIT_PDC);
		return pdc.getLongOr(EXPIRABLE_KEY, NO_DEADLINE);
	}

	/**
	 * Formats a remaining duration.
	 *
	 * <p>{@code withSeconds} false reproduces the server's own lore line, which <em>truncates</em>
	 * rather than rounds — 2h 25m 53s displays there as "2h 25m", verified against three samples. The
	 * truncation is deliberate here so the HUD agrees with the tooltip instead of being a minute
	 * ahead of it.
	 */
	public static String format(long remainingMillis, boolean withSeconds) {
		long total = Math.max(0, remainingMillis) / 1000;
		long hours = total / 3600;
		long minutes = (total % 3600) / 60;
		long seconds = total % 60;
		if (withSeconds) {
			return hours > 0
					? "%dh %02dm %02ds".formatted(hours, minutes, seconds)
					: (minutes > 0 ? "%dm %02ds".formatted(minutes, seconds) : "%ds".formatted(seconds));
		}
		return hours > 0 ? "%dh %dm".formatted(hours, minutes) : "%dm".formatted(minutes);
	}
}

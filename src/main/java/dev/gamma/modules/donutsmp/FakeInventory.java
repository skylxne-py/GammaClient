package dev.gamma.modules.donutsmp;

import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.ItemListSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Client-side display substitution for everything on screen that tells a viewer what you have:
 * the hotbar, your own inventory slots, and the experience bar. (The scoreboard sidebar is
 * {@link ScoreboardEditor}'s.) The sibling of {@link FakeCoordinates} and {@link NameProtect}, and like both of them
 * it is <em>only</em> a render-time swap — nothing here touches an {@code ItemStack} the game or
 * the server owns, so clicking, dragging, using, and dropping all operate on your real items and
 * behave exactly as they would with the module off.
 *
 * <p>That split is the reason the held item is real while the hotbar is fake. The hotbar is drawn
 * by {@code Hud}; the item in your hand is drawn by the first-person item renderer, which this
 * module never goes near. Selecting a slot therefore puts the true item in your hand while the
 * hotbar keeps showing the disguise — which is what makes it usable at all rather than a blindfold.
 *
 * <h2>The disguise is a stable permutation, not per-frame noise</h2>
 *
 * <p>A random item picked afresh each frame would strobe, and a random item picked per slot would
 * scramble every time you moved something. Instead the mapping is from <em>item identity</em> to
 * item identity, seeded once when the module is enabled: your diamond pickaxe shows as the same
 * wrong item for the whole session, in every slot, in the hotbar and in the inventory alike. It
 * looks completely static to a viewer, and you learn your own substitutions in about a minute and
 * can still find your gear.
 *
 * <p>Empty slots stay empty and stack counts are preserved (clamped to what the disguise can
 * legally stack to, so a sword never renders "64"). Keeping the shape of the inventory real is
 * what stops it looking generated; it is the identities that give you away, not the layout.
 *
 * <h2>What it deliberately does not cover</h2>
 *
 * <p>Hovering a slot still shows the real item's tooltip. Spoofing that too would leave no way to
 * inspect enchantments or durability while the module is on, and the tooltip only exists under
 * your own cursor for as long as you hold it there. If it needs to go as well, it is a one-line
 * addition to {@code ContainerScreenSpoofMixin} — it is left out on purpose, not by omission.
 *
 * <p>The experience <em>level</em> is only drawn by vanilla when you actually have levels, so
 * {@code ExperienceBar} can disguise the number you have but cannot invent one you don't.
 */
public final class FakeInventory extends Module {

	/**
	 * What disguises are drawn from. Deliberately mundane survival gear: the point is to look like
	 * somebody's ordinary inventory, and a pool full of spawn eggs and command blocks announces
	 * itself instantly. Editable, so a pool that suits the server you're on is one setting away.
	 */
	private static final List<Item> DEFAULT_POOL = List.of(
			Items.COBBLESTONE, Items.OAK_PLANKS, Items.DIRT, Items.TORCH, Items.LADDER,
			Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND, Items.COAL, Items.REDSTONE,
			Items.BREAD, Items.COOKED_BEEF, Items.GOLDEN_CARROT, Items.WHEAT,
			Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.IRON_SHOVEL, Items.IRON_AXE,
			Items.IRON_SWORD, Items.BOW, Items.ARROW, Items.SHIELD,
			Items.WATER_BUCKET, Items.OBSIDIAN, Items.CRAFTING_TABLE, Items.FURNACE,
			Items.CHEST, Items.GLASS, Items.SAND, Items.GRAVEL,
			Items.STICK, Items.FLINT, Items.LEATHER, Items.STRING, Items.GUNPOWDER
	);

	public static volatile FakeInventory instance;

	private final BoolSetting hotbar = register(new BoolSetting("Hotbar", "Disguise the hotbar and offhand on the HUD. The item actually in your hand is untouched.", true));
	private final BoolSetting inventory = register(new BoolSetting("Inventory", "Disguise your own inventory slots in any container screen. Chest/container slots are left alone.", true));
	private final BoolSetting experienceBar = register(new BoolSetting("ExperienceBar", "Show a made-up level and bar fill instead of your real experience.", false));
	private final ItemListSetting pool = register(new ItemListSetting("Pool", "Items disguises are drawn from.", DEFAULT_POOL));

	/**
	 * Re-rolled on every enable rather than persisted. A disguise that survived restarts would be
	 * a fingerprint of its own — anyone who saw your "inventory" twice would know it never changes.
	 */
	private long seed;

	public FakeInventory() {
		super("FakeInventory", "Shows made-up items, experience and scores on screen instead of your real ones.", Category.DONUT_SMP);
		instance = this;
	}

	@Override
	protected void onEnable() {
		seed = ThreadLocalRandom.current().nextLong();
	}

	public boolean hotbarSpoofed() {
		return isEnabled() && hotbar.get();
	}

	public boolean inventorySpoofed() {
		return isEnabled() && inventory.get();
	}

	public boolean experienceSpoofed() {
		return isEnabled() && experienceBar.get();
	}

	/**
	 * The stack to draw in place of {@code real}. Never returns null and never mutates its
	 * argument — callers are render paths handing the result straight to a draw call.
	 */
	public ItemStack disguise(ItemStack real) {
		if (real.isEmpty()) {
			return real;
		}
		List<Item> items = pool.get();
		if (items.isEmpty()) {
			return real;
		}
		Item fake = items.get(Math.floorMod(mix(seed ^ BuiltInRegistries.ITEM.getId(real.getItem())), items.size()));
		if (fake == real.getItem()) {
			return real;
		}
		ItemStack disguised = new ItemStack(fake);
		disguised.setCount(Math.min(real.getCount(), disguised.getMaxStackSize()));
		return disguised;
	}

	/**
	 * Keyed on the real level so the number holds still frame to frame but does move when you
	 * actually gain a level — a level counter frozen through an hour of mining is a tell.
	 */
	public int fakeExperienceLevel(int realLevel) {
		return 1 + Math.floorMod(mix(seed ^ (realLevel * 0x9E3779B1L)), 60);
	}

	/**
	 * Same idea for the bar, quantised into eight buckets of the real progress: it advances a few
	 * times per level like a real bar does, without twitching on every orb picked up.
	 */
	public float fakeExperienceProgress(int realLevel, float realProgress) {
		long bucket = (long) (Math.max(0.0f, Math.min(1.0f, realProgress)) * 8.0f);
		return Math.floorMod(mix(seed ^ (realLevel * 31L) ^ (bucket * 0x27D4EB2FL)), 1000) / 1000.0f;
	}

	/** SplitMix64 finalizer — cheap, and spreads the low bits the callers all take modulo of. */
	private static long mix(long value) {
		long z = value + 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}
}

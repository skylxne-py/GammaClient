package dev.gamma.modules.donutsmp;

import dev.gamma.Gamma;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.DoubleSetting;
import dev.gamma.config.setting.EnumSetting;
import dev.gamma.config.setting.IntSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.core.event.Subscription;
import dev.gamma.core.event.events.TickEvent;
import dev.gamma.util.ExpirableItem;
import dev.gamma.util.NotificationSound;
import dev.gamma.util.SoundNotifier;
import dev.gamma.util.ToastNotifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Tracks the self-destruct deadline on the held item and warns before it runs out.
 *
 * <p>Extraction/render split: the deadline is read and cached here on tick, and
 * {@link dev.gamma.gui.hud.elements.ShardItemTimerElement} only formats the cached snapshot. That isn't
 * ceremony — {@code CustomData.copyTag()} allocates a fresh {@code CompoundTag} on every call, so
 * reading it per frame instead of per tick would churn garbage at framerate for a number that
 * changes once a second.
 *
 * <p>See {@link ExpirableItem} for the on-item encoding and why a purely client-side countdown is
 * accurate.
 */
public final class ShardItemTimer extends Module {

	public static volatile ShardItemTimer instance;

	private final BoolSetting showSeconds = register(new BoolSetting("ShowSeconds", "Show seconds. Off matches the server's own tooltip, which truncates to whole minutes.", true));
	private final BoolSetting showName = register(new BoolSetting("ShowName", "Prefix the countdown with the item's name.", true));
	private final IntSetting warnMinutes = register(new IntSetting("WarnMinutes", "Warn once when the held item drops below this many minutes remaining. 0 disables the warning.", 5, 0, 120));
	private final BoolSetting sound = register(new BoolSetting("Sound", "Play a chime when the warning fires.", true));
	private final DoubleSetting soundVolume = register(new DoubleSetting("SoundVolume", "Chime volume.", 1.0, 0.0, SoundNotifier.MAX_VOLUME));
	private final EnumSetting<NotificationSound> soundSample = register(new EnumSetting<>("SoundSample", "Which sample the chime uses.", NotificationSound.class, NotificationSound.AMETHYST));
	private final DoubleSetting soundPitch = register(new DoubleSetting("SoundPitch", "Playback pitch.", 1.0, 0.5, 2.0));
	private final BoolSetting toast = register(new BoolSetting("Toast", "Show a notification above the hotbar when the warning fires.", true));

	private final SoundNotifier chime = new SoundNotifier(500);
	private final ToastNotifier toasts = new ToastNotifier(2_000);

	/** Null when nothing expirable is held. Volatile: written on tick, read on the render thread. */
	private volatile Snapshot snapshot;

	/**
	 * The deadline the warning has already fired for. Keyed on the deadline rather than a boolean so
	 * swapping to a different expirable item re-arms it, while putting the same one away and taking
	 * it out again does not re-fire.
	 */
	private long warnedDeadline = ExpirableItem.NO_DEADLINE;

	/** Module.listen() has no automatic teardown, so a toggle without this stacks a second tick handler. */
	private Subscription tickSubscription;

	public ShardItemTimer() {
		super("ShardItemTimer", "Counts down DonutSMP self-destructing items (Shard Pickaxe and friends) and warns before they vanish.", Category.DONUT_SMP);
		instance = this;
	}

	/** What the HUD element draws. {@code deadlineMillis} is absolute, so it stays correct between ticks. */
	public record Snapshot(String itemName, long deadlineMillis) {

		public long remainingMillis() {
			return deadlineMillis - System.currentTimeMillis();
		}
	}

	@Override
	protected void onEnable() {
		snapshot = null;
		warnedDeadline = ExpirableItem.NO_DEADLINE;
		tickSubscription = listen(TickEvent.class, this::onTick);
	}

	@Override
	protected void onDisable() {
		Gamma.EVENT_BUS.unsubscribe(tickSubscription);
		snapshot = null;
	}

	public Snapshot snapshot() {
		return snapshot;
	}

	public boolean showSeconds() {
		return showSeconds.get();
	}

	public boolean showName() {
		return showName.get();
	}

	/** Remaining-time cutoff the HUD element recolours at, in millis; 0 when warnings are off. */
	public long warnThresholdMillis() {
		return warnMinutes.get() * 60_000L;
	}

	private void onTick(TickEvent event) {
		if (event.phase() != TickEvent.Phase.END) {
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			snapshot = null;
			return;
		}
		ItemStack stack = player.getMainHandItem();
		long deadline = ExpirableItem.deadlineMillis(stack);
		if (deadline == ExpirableItem.NO_DEADLINE) {
			stack = player.getOffhandItem();
			deadline = ExpirableItem.deadlineMillis(stack);
		}
		if (deadline == ExpirableItem.NO_DEADLINE) {
			snapshot = null;
			return;
		}
		snapshot = new Snapshot(stack.getHoverName().getString(), deadline);
		maybeWarn(deadline, snapshot.remainingMillis());
	}

	private void maybeWarn(long deadline, long remaining) {
		long threshold = warnThresholdMillis();
		if (threshold <= 0 || remaining > threshold || remaining <= 0 || deadline == warnedDeadline) {
			return;
		}
		warnedDeadline = deadline;
		if (sound.get()) {
			chime.play(soundSample.get().event(), soundPitch.get().floatValue(), soundVolume.get());
		}
		if (toast.get()) {
			Snapshot current = snapshot;
			String name = current != null ? current.itemName() : "Item";
			toasts.show("Expiring soon", "%s — %s left".formatted(name, ExpirableItem.format(remaining, true)));
		}
	}
}

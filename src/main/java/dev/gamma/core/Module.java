package dev.gamma.core;

import dev.gamma.Gamma;
import dev.gamma.config.setting.KeybindSetting;
import dev.gamma.config.setting.Setting;
import dev.gamma.core.event.EventListener;
import dev.gamma.core.event.GammaEvent;
import dev.gamma.core.event.Priority;
import dev.gamma.core.event.Subscription;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base of every feature. Per the project's module contract: category, name, description,
 * default keybind, onEnable/onDisable, and settings declared as fields — registered
 * explicitly (no reflection) via {@link #register}.
 */
public abstract class Module {

	private final String name;
	private final String description;
	private final Category category;
	private final List<Setting<?>> settings = new ArrayList<>();
	private final KeybindSetting keybind;
	private boolean enabled;

	protected Module(String name, String description, Category category, KeybindSetting.Bind defaultKeybind) {
		this.name = name;
		this.description = description;
		this.category = category;
		this.keybind = register(new KeybindSetting("Bind", "Key to toggle " + name + ".", defaultKeybind));
	}

	protected Module(String name, String description, Category category) {
		this(name, description, category, KeybindSetting.Bind.unbound(KeybindSetting.Mode.TOGGLE));
	}

	/** Adds a setting to this module's declared settings and returns it, for use directly in field initializers. */
	protected final <S extends Setting<?>> S register(S setting) {
		settings.add(setting);
		return setting;
	}

	public final String name() {
		return name;
	}

	public final String description() {
		return description;
	}

	public final Category category() {
		return category;
	}

	public final List<Setting<?>> settings() {
		return Collections.unmodifiableList(settings);
	}

	public final KeybindSetting keybind() {
		return keybind;
	}

	public final boolean isEnabled() {
		return enabled;
	}

	public final void setEnabled(boolean enabled) {
		if (this.enabled == enabled) {
			return;
		}
		this.enabled = enabled;
		if (enabled) {
			onEnable();
		} else {
			onDisable();
		}
	}

	public final void toggle() {
		setEnabled(!enabled);
	}

	/**
	 * Whether a keybind flipping this module should say so in chat.
	 *
	 * <p>Overridable rather than a check on bind mode in {@link dev.gamma.core.keybind.KeybindManager}:
	 * the reason a module doesn't want announcing is a property of the module, not of how it happens
	 * to be bound today. {@code Freelook} is the case that motivated it — it is held down constantly,
	 * so it would produce two chat lines per glance — but rebinding it to TOGGLE wouldn't make those
	 * lines wanted, which is exactly what keying off the mode would do.
	 */
	public boolean announcesToggle() {
		return true;
	}

	/**
	 * Whether this belongs in the settings screen rather than in its category's list.
	 *
	 * <p>For the handful of modules that are integrations with the client itself rather than features
	 * of the game — {@code DiscordRPC} is the case this exists for. It is still a {@link Module},
	 * because it has real enable/disable work to do and settings to persist, and rebuilding all of
	 * that outside the module system to move where it is listed would be paying a lot for a menu
	 * position. This only changes where it is listed.
	 */
	public boolean isClientLevel() {
		return false;
	}

	protected void onEnable() {
	}

	protected void onDisable() {
	}

	/**
	 * Subscribes to {@link Gamma#EVENT_BUS}, wrapping {@code listener} so a thrown exception is
	 * caught, this module is disabled, and the failure is reported in chat (Phase 7 hardening)
	 * instead of propagating into the tick/render call stack and crashing the client. Every
	 * module should subscribe through this rather than {@code Gamma.EVENT_BUS.subscribe}
	 * directly so that guarantee is automatic, not opt-in.
	 */
	protected final <T extends GammaEvent> Subscription listen(Class<T> type, EventListener<T> listener) {
		return listen(type, Priority.NORMAL, listener);
	}

	protected final <T extends GammaEvent> Subscription listen(Class<T> type, int priority, EventListener<T> listener) {
		return Gamma.EVENT_BUS.subscribe(type, priority, guarded(type, listener));
	}

	private <T extends GammaEvent> EventListener<T> guarded(Class<T> type, EventListener<T> listener) {
		return event -> {
			// A module that disables itself part-way through a dispatch still gets called for the
			// rest of it: EventBus.post walks a snapshot of the handler array, so unsubscribing
			// inside onDisable can't remove a handler the loop already holds. Zoom hit this — the
			// keybind handler disabled it mid-tick, onDisable restored the FOV, and then Zoom's own
			// already-captured tick handler ran, re-read the (now restored) FOV as its "original",
			// and lerped the zoom straight back in with nothing left subscribed to ever undo it.
			// Modules only subscribe while enabled, so dropping the event here is always correct
			// and fixes the whole class of bug rather than one module's symptom.
			if (!enabled) {
				return;
			}
			long start = System.nanoTime();
			try {
				listener.handle(event);
			} catch (Exception e) {
				ModuleFaultHandler.handle(this, e);
			} finally {
				ModuleProfiler.record(this, type, System.nanoTime() - start);
			}
		};
	}
}

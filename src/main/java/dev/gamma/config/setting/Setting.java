package dev.gamma.config.setting;

import com.google.gson.JsonElement;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Base of every configurable module option. Subclasses (see package) each own their JSON
 * shape; {@link dev.gamma.config.ConfigManager} only ever calls {@link #toJson()} /
 * {@link #fromJson(JsonElement)} and never inspects the value type directly.
 *
 * @param <T> the value type this setting holds
 */
public abstract class Setting<T> {

	private final String name;
	private final String description;
	private final T defaultValue;
	private final BooleanSupplier visible;
	private T value;

	protected Setting(String name, String description, T defaultValue, BooleanSupplier visible) {
		this.name = name;
		this.description = description;
		this.defaultValue = copy(defaultValue);
		this.value = copy(defaultValue);
		this.visible = visible;
	}

	protected Setting(String name, String description, T defaultValue) {
		this(name, description, defaultValue, () -> true);
	}

	public final String name() {
		return name;
	}

	public final String description() {
		return description;
	}

	public final T get() {
		return value;
	}

	public final T defaultValue() {
		return defaultValue;
	}

	public final void set(T value) {
		this.value = copy(coerce(Objects.requireNonNull(value)));
	}

	public final void reset() {
		this.value = copy(defaultValue);
	}

	/** Hook for subclasses that need to clamp or normalize (e.g. {@link IntSetting} range clamping). */
	protected T coerce(T value) {
		return value;
	}

	/**
	 * Hook for subclasses whose value is mutable (list settings) so stored/default/current
	 * values never alias the same instance. Runs from the constructor before subclass fields
	 * are set, so implementations must not read subclass state — only wrap {@code value}.
	 */
	protected T copy(T value) {
		return value;
	}

	/** Whether the GUI should currently show this setting — e.g. a "tracers" toggle hidden until "box" mode is off. */
	public final boolean isVisible() {
		return visible.getAsBoolean();
	}

	public abstract JsonElement toJson();

	public abstract void fromJson(JsonElement json);
}

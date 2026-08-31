package dev.gamma.config.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.function.BooleanSupplier;

public final class IntSetting extends Setting<Integer> {

	private final int min;
	private final int max;

	public IntSetting(String name, String description, int defaultValue, int min, int max, BooleanSupplier visible) {
		super(name, description, clamp(defaultValue, min, max), visible);
		this.min = min;
		this.max = max;
	}

	public IntSetting(String name, String description, int defaultValue, int min, int max) {
		this(name, description, defaultValue, min, max, () -> true);
	}

	public int min() {
		return min;
	}

	public int max() {
		return max;
	}

	@Override
	protected Integer coerce(Integer value) {
		return clamp(value, min, max);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(get());
	}

	@Override
	public void fromJson(JsonElement json) {
		set(json.getAsInt());
	}
}

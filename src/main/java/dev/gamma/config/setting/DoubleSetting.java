package dev.gamma.config.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.function.BooleanSupplier;

public final class DoubleSetting extends Setting<Double> {

	private final double min;
	private final double max;

	public DoubleSetting(String name, String description, double defaultValue, double min, double max, BooleanSupplier visible) {
		super(name, description, clamp(defaultValue, min, max), visible);
		this.min = min;
		this.max = max;
	}

	public DoubleSetting(String name, String description, double defaultValue, double min, double max) {
		this(name, description, defaultValue, min, max, () -> true);
	}

	public double min() {
		return min;
	}

	public double max() {
		return max;
	}

	@Override
	protected Double coerce(Double value) {
		return clamp(value, min, max);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(get());
	}

	@Override
	public void fromJson(JsonElement json) {
		set(json.getAsDouble());
	}
}

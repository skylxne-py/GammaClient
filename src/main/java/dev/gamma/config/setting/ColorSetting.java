package dev.gamma.config.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.function.BooleanSupplier;

/** ARGB packed into an int, per project convention ("Colors are int ARGB throughout, converted at the boundary"). */
public final class ColorSetting extends Setting<Integer> {

	public ColorSetting(String name, String description, int defaultArgb, BooleanSupplier visible) {
		super(name, description, defaultArgb, visible);
	}

	public ColorSetting(String name, String description, int defaultArgb) {
		super(name, description, defaultArgb);
	}

	@Override
	public JsonElement toJson() {
		// Signed decimal round-trips fine through Gson; stored as a plain int, not hex, to keep parsing trivial.
		return new JsonPrimitive(get());
	}

	@Override
	public void fromJson(JsonElement json) {
		set(json.getAsInt());
	}
}

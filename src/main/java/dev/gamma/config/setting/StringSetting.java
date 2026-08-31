package dev.gamma.config.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.function.BooleanSupplier;

public final class StringSetting extends Setting<String> {

	public StringSetting(String name, String description, String defaultValue, BooleanSupplier visible) {
		super(name, description, defaultValue, visible);
	}

	public StringSetting(String name, String description, String defaultValue) {
		super(name, description, defaultValue);
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(get());
	}

	@Override
	public void fromJson(JsonElement json) {
		set(json.getAsString());
	}
}

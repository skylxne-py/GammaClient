package dev.gamma.config.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.function.BooleanSupplier;

public final class BoolSetting extends Setting<Boolean> {

	public BoolSetting(String name, String description, boolean defaultValue, BooleanSupplier visible) {
		super(name, description, defaultValue, visible);
	}

	public BoolSetting(String name, String description, boolean defaultValue) {
		super(name, description, defaultValue);
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(get());
	}

	@Override
	public void fromJson(JsonElement json) {
		set(json.getAsBoolean());
	}
}

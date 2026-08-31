package dev.gamma.config.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.gamma.Gamma;

import java.util.function.BooleanSupplier;

public final class EnumSetting<E extends Enum<E>> extends Setting<E> {

	private final Class<E> type;

	public EnumSetting(String name, String description, Class<E> type, E defaultValue, BooleanSupplier visible) {
		super(name, description, defaultValue, visible);
		this.type = type;
	}

	public EnumSetting(String name, String description, Class<E> type, E defaultValue) {
		super(name, description, defaultValue);
		this.type = type;
	}

	public E[] options() {
		return type.getEnumConstants();
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(get().name());
	}

	@Override
	public void fromJson(JsonElement json) {
		String stored = json.getAsString();
		try {
			set(Enum.valueOf(type, stored));
		} catch (IllegalArgumentException e) {
			Gamma.LOGGER.warn("Setting '{}': saved value '{}' no longer exists on {}, falling back to default", name(), stored, type.getSimpleName());
			reset();
		}
	}
}

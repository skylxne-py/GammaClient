package dev.gamma.config.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.function.BooleanSupplier;

/** A rebindable key, consumed by {@code core.keybind.KeybindManager}. Not tied to vanilla's KeyMapping/Controls screen. */
public final class KeybindSetting extends Setting<KeybindSetting.Bind> {

	public static final int UNBOUND = -1;

	public KeybindSetting(String name, String description, Bind defaultValue, BooleanSupplier visible) {
		super(name, description, defaultValue, visible);
	}

	public KeybindSetting(String name, String description, Bind defaultValue) {
		super(name, description, defaultValue);
	}

	@Override
	public JsonElement toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("key", get().keyCode());
		json.addProperty("mode", get().mode().name());
		return json;
	}

	@Override
	public void fromJson(JsonElement json) {
		JsonObject object = json.getAsJsonObject();
		int keyCode = object.get("key").getAsInt();
		Mode mode = Mode.valueOf(object.get("mode").getAsString());
		set(new Bind(keyCode, mode));
	}

	public enum Mode {
		HOLD,
		TOGGLE
	}

	/** {@code keyCode} is a GLFW/InputConstants key code, or {@link #UNBOUND}. */
	public record Bind(int keyCode, Mode mode) {

		public static Bind unbound(Mode mode) {
			return new Bind(UNBOUND, mode);
		}

		public boolean isBound() {
			return keyCode != UNBOUND;
		}
	}
}

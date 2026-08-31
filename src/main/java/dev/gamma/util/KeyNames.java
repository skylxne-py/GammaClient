package dev.gamma.util;

import com.mojang.blaze3d.platform.InputConstants;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Friendly key names (for the {@code .bind} command and, later, the GUI) mapped to
 * {@code InputConstants} key codes. A fixed, hand-written table, not a reflective scan of the
 * KEY_* constants — keeps lookups a plain map get with no classloading surprises.
 */
public final class KeyNames {

	private static final Map<String, Integer> BY_NAME = new HashMap<>();
	private static final Map<Integer, String> BY_CODE = new HashMap<>();

	static {
		put("A", InputConstants.KEY_A);
		put("B", InputConstants.KEY_B);
		put("C", InputConstants.KEY_C);
		put("D", InputConstants.KEY_D);
		put("E", InputConstants.KEY_E);
		put("F", InputConstants.KEY_F);
		put("G", InputConstants.KEY_G);
		put("H", InputConstants.KEY_H);
		put("I", InputConstants.KEY_I);
		put("J", InputConstants.KEY_J);
		put("K", InputConstants.KEY_K);
		put("L", InputConstants.KEY_L);
		put("M", InputConstants.KEY_M);
		put("N", InputConstants.KEY_N);
		put("O", InputConstants.KEY_O);
		put("P", InputConstants.KEY_P);
		put("Q", InputConstants.KEY_Q);
		put("R", InputConstants.KEY_R);
		put("S", InputConstants.KEY_S);
		put("T", InputConstants.KEY_T);
		put("U", InputConstants.KEY_U);
		put("V", InputConstants.KEY_V);
		put("W", InputConstants.KEY_W);
		put("X", InputConstants.KEY_X);
		put("Y", InputConstants.KEY_Y);
		put("Z", InputConstants.KEY_Z);
		put("0", InputConstants.KEY_0);
		put("1", InputConstants.KEY_1);
		put("2", InputConstants.KEY_2);
		put("3", InputConstants.KEY_3);
		put("4", InputConstants.KEY_4);
		put("5", InputConstants.KEY_5);
		put("6", InputConstants.KEY_6);
		put("7", InputConstants.KEY_7);
		put("8", InputConstants.KEY_8);
		put("9", InputConstants.KEY_9);
		put("F1", InputConstants.KEY_F1);
		put("F2", InputConstants.KEY_F2);
		put("F3", InputConstants.KEY_F3);
		put("F4", InputConstants.KEY_F4);
		put("F5", InputConstants.KEY_F5);
		put("F6", InputConstants.KEY_F6);
		put("F7", InputConstants.KEY_F7);
		put("F8", InputConstants.KEY_F8);
		put("F9", InputConstants.KEY_F9);
		put("F10", InputConstants.KEY_F10);
		put("F11", InputConstants.KEY_F11);
		put("F12", InputConstants.KEY_F12);
		put("SPACE", InputConstants.KEY_SPACE);
		put("TAB", InputConstants.KEY_TAB);
		put("ENTER", InputConstants.KEY_RETURN);
		put("RETURN", InputConstants.KEY_RETURN);
		put("ESCAPE", InputConstants.KEY_ESCAPE);
		put("BACKSPACE", InputConstants.KEY_BACKSPACE);
		put("DELETE", InputConstants.KEY_DELETE);
		put("INSERT", InputConstants.KEY_INSERT);
		put("HOME", InputConstants.KEY_HOME);
		put("END", InputConstants.KEY_END);
		put("PAGEUP", InputConstants.KEY_PAGEUP);
		put("PAGEDOWN", InputConstants.KEY_PAGEDOWN);
		put("UP", InputConstants.KEY_UP);
		put("DOWN", InputConstants.KEY_DOWN);
		put("LEFT", InputConstants.KEY_LEFT);
		put("RIGHT", InputConstants.KEY_RIGHT);
		put("LSHIFT", InputConstants.KEY_LSHIFT);
		put("RSHIFT", InputConstants.KEY_RSHIFT);
		put("LCONTROL", InputConstants.KEY_LCONTROL);
		put("RCONTROL", InputConstants.KEY_RCONTROL);
		put("LALT", InputConstants.KEY_LALT);
		put("RALT", InputConstants.KEY_RALT);
		put("CAPSLOCK", InputConstants.KEY_CAPSLOCK);
		put("GRAVE", InputConstants.KEY_GRAVE);
	}

	private KeyNames() {
	}

	public static OptionalInt parse(String name) {
		Integer code = BY_NAME.get(name.toUpperCase(Locale.ROOT));
		return code == null ? OptionalInt.empty() : OptionalInt.of(code);
	}

	public static String name(int keyCode) {
		return BY_CODE.getOrDefault(keyCode, "KEY_" + keyCode);
	}

	private static void put(String name, int keyCode) {
		BY_NAME.put(name, keyCode);
		BY_CODE.putIfAbsent(keyCode, name);
	}
}

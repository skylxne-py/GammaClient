package dev.gamma.core.event.events;

import dev.gamma.core.event.GammaEvent;

/**
 * Fired on the tick a key's down-state changes (edge-detected from
 * {@code InputConstants.isKeyDown} polling — see {@code core.keybind.KeybindManager}), not once
 * per GLFW callback. {@code keyCode} is a GLFW/{@code InputConstants} key code.
 */
public record KeyInputEvent(int keyCode, boolean pressed) implements GammaEvent {
}

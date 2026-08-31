package dev.gamma.gui.clickgui;

import dev.gamma.core.keybind.TextInputCapture;

/**
 * Marks the screen that is "the Gamma menu" — currently only {@link ModernGuiScreen}.
 *
 * <p>{@link ClickGuiOpener} needs to recognise its own screen to toggle it closed, and to know
 * whether text entry has the keyboard before acting on the bind. Kept as an interface rather than a
 * test against the concrete class so that swapping or adding a menu screen does not mean hunting
 * down instanceof checks — which is exactly what retiring the previous one would otherwise have been.
 */
public interface GammaGuiScreen extends TextInputCapture {
}

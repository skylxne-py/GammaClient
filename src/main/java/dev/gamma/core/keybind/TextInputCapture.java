package dev.gamma.core.keybind;

/**
 * Implemented by screens with their own hand-rolled text entry (the ClickGUI's search bar,
 * string settings, keybind capture, color picker hex field) that don't go through vanilla's
 * {@code EditBox}/focus system — {@link KeybindManager} checks this in addition to {@code
 * EditBox} focus so a module keybind can't fire while the user is typing into one of these.
 */
public interface TextInputCapture {

	boolean isCapturingTextInput();
}

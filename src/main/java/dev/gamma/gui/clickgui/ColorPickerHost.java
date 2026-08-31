package dev.gamma.gui.clickgui;

import dev.gamma.config.setting.ColorSetting;

/** Implemented by {@link ModernGuiScreen}: lets a {@code ColorSettingWidget} request the shared color picker popup. */
public interface ColorPickerHost {

	/**
	 * Opens the shared picker for {@code setting}, anchored to the swatch that was clicked.
	 *
	 * <p>The whole swatch rectangle is passed rather than one corner because the host decides
	 * which side of it the popup opens on, and needs both edges to flip cleanly. A widget knows
	 * neither how big the popup is nor where the screen ends, so it must not make that call.
	 */
	void openColorPicker(ColorSetting setting, int swatchX, int swatchY, int swatchWidth, int swatchHeight);
}

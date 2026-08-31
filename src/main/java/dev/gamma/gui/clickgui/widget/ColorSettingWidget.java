package dev.gamma.gui.clickgui.widget;

import dev.gamma.config.setting.ColorSetting;
import dev.gamma.gui.clickgui.ColorPickerHost;
import dev.gamma.gui.clickgui.Theme;
import dev.gamma.render.Renderer2D;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;

/** A rounded color swatch; clicking it opens the shared {@link dev.gamma.gui.clickgui.ColorPickerPopup} via the host screen. */
public final class ColorSettingWidget extends SettingWidget {

	private static final int SWATCH_SIZE = 16;

	private final ColorSetting colorSetting;
	private final ColorPickerHost host;

	public ColorSettingWidget(ColorSetting setting, Theme theme, ColorPickerHost host) {
		super(setting, theme);
		this.colorSetting = setting;
		this.host = host;
	}

	@Override
	public void render(Renderer2D renderer, Font font, int mouseX, int mouseY, float partialTick) {
		renderer.text(font, setting.name(), x, y + (height() - font.lineHeight) / 2, theme.textPrimary());

		int swatchX = x + width - SWATCH_SIZE;
		int swatchY = y + (height() - SWATCH_SIZE) / 2;
		renderer.roundedRectOutline(swatchX - 2, swatchY - 2, SWATCH_SIZE + 4, SWATCH_SIZE + 4, 6, 1.5f, theme.textSecondary());
		renderer.roundedRect(swatchX, swatchY, SWATCH_SIZE, SWATCH_SIZE, 4, colorSetting.get());
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0 || !isHovered(event.x(), event.y())) {
			return false;
		}
		host.openColorPicker(colorSetting, x + width - SWATCH_SIZE, y + (height() - SWATCH_SIZE) / 2, SWATCH_SIZE, SWATCH_SIZE);
		return true;
	}
}

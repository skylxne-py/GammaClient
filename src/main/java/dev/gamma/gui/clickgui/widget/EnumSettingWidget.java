package dev.gamma.gui.clickgui.widget;

import dev.gamma.config.setting.EnumSetting;
import dev.gamma.gui.clickgui.Theme;
import dev.gamma.render.Renderer2D;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;

/** A cycle button: arrows step through {@link EnumSetting#options()}, the pill in between shows the current name. */
public final class EnumSettingWidget<E extends Enum<E>> extends SettingWidget {

	private static final int ARROW_WIDTH = 14;

	private final EnumSetting<E> enumSetting;

	public EnumSettingWidget(EnumSetting<E> setting, Theme theme) {
		super(setting, theme);
		this.enumSetting = setting;
	}

	@Override
	public void render(Renderer2D renderer, Font font, int mouseX, int mouseY, float partialTick) {
		int labelY = y + (height() - font.lineHeight) / 2;
		renderer.text(font, setting.name(), x, labelY, theme.textPrimary());

		String value = displayName(enumSetting.get());
		int pillRight = x + width - ARROW_WIDTH - 2;
		int pillLeft = pillRight - font.width(value) - 16;
		renderer.roundedRect(pillLeft, y + 1, pillRight - pillLeft, height() - 2, (height() - 2) / 2, theme.settingsBackground());
		renderer.text(font, value, pillLeft + 8, labelY, theme.accent());

		boolean rightHover = mouseX >= pillRight + 2 && mouseX < pillRight + 2 + ARROW_WIDTH && mouseY >= y && mouseY < y + height();
		boolean leftHover = mouseX >= pillLeft - ARROW_WIDTH - 2 && mouseX < pillLeft - 2 && mouseY >= y && mouseY < y + height();
		renderer.text(font, "<", pillLeft - ARROW_WIDTH, labelY, leftHover ? theme.accentHover() : theme.textSecondary());
		renderer.text(font, ">", pillRight + 4, labelY, rightHover ? theme.accentHover() : theme.textSecondary());
	}

	private String displayName(E value) {
		String name = value.name();
		return name.charAt(0) + name.substring(1).toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0 || !isHovered(event.x(), event.y())) {
			return false;
		}
		E[] options = enumSetting.options();
		int index = indexOf(options, enumSetting.get());
		boolean rightHalf = event.x() > x + width / 2.0;
		int next = rightHalf ? (index + 1) % options.length : (index - 1 + options.length) % options.length;
		enumSetting.set(options[next]);
		return true;
	}

	private int indexOf(E[] options, E value) {
		for (int i = 0; i < options.length; i++) {
			if (options[i] == value) {
				return i;
			}
		}
		return 0;
	}
}

package dev.gamma.gui.clickgui;

import dev.gamma.config.setting.BlockListSetting;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.ColorSetting;
import dev.gamma.config.setting.DoubleSetting;
import dev.gamma.config.setting.EntityTypeListSetting;
import dev.gamma.config.setting.EnumSetting;
import dev.gamma.config.setting.IntSetting;
import dev.gamma.config.setting.ItemListSetting;
import dev.gamma.config.setting.KeybindSetting;
import dev.gamma.config.setting.Setting;
import dev.gamma.config.setting.StringSetting;
import dev.gamma.gui.clickgui.widget.BlockListSettingWidget;
import dev.gamma.gui.clickgui.widget.ColorSettingWidget;
import dev.gamma.gui.clickgui.widget.EntityTypeListSettingWidget;
import dev.gamma.gui.clickgui.widget.EnumSettingWidget;
import dev.gamma.gui.clickgui.widget.ItemListSettingWidget;
import dev.gamma.gui.clickgui.widget.KeybindSettingWidget;
import dev.gamma.gui.clickgui.widget.SettingWidget;
import dev.gamma.gui.clickgui.widget.SliderSettingWidget;
import dev.gamma.gui.clickgui.widget.StringSettingWidget;
import dev.gamma.gui.clickgui.widget.ToggleSwitchWidget;

/** Builds the right {@link SettingWidget} for a declared {@link Setting}, keyed on its concrete type. */
public final class WidgetFactory {

	private WidgetFactory() {
	}

	public static SettingWidget create(Setting<?> setting, Theme theme, ColorPickerHost colorPickerHost) {
		return switch (setting) {
			case BoolSetting bool -> new ToggleSwitchWidget(bool, theme);
			case DoubleSetting doubleSetting -> SliderSettingWidget.forDouble(doubleSetting, theme);
			case IntSetting intSetting -> SliderSettingWidget.forInt(intSetting, theme);
			case ColorSetting color -> new ColorSettingWidget(color, theme, colorPickerHost);
			case KeybindSetting keybind -> new KeybindSettingWidget(keybind, theme);
			case StringSetting string -> new StringSettingWidget(string, theme);
			case BlockListSetting blockList -> new BlockListSettingWidget(blockList, theme);
			case EntityTypeListSetting entityTypeList -> new EntityTypeListSettingWidget(entityTypeList, theme);
			case ItemListSetting itemList -> new ItemListSettingWidget(itemList, theme);
			case EnumSetting<?> enumSetting -> createEnumWidget(enumSetting, theme);
			default -> throw new IllegalArgumentException("No ClickGUI widget for setting type " + setting.getClass());
		};
	}

	private static <E extends Enum<E>> SettingWidget createEnumWidget(EnumSetting<E> setting, Theme theme) {
		return new EnumSettingWidget<>(setting, theme);
	}
}

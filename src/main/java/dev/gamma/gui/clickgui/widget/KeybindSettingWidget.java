package dev.gamma.gui.clickgui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import dev.gamma.config.setting.KeybindSetting;
import dev.gamma.gui.clickgui.Theme;
import dev.gamma.render.Renderer2D;
import dev.gamma.util.KeyNames;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/** Click the pill, then press a key to bind it (or Escape to unbind) — no separate "press any key" dialog. */
public final class KeybindSettingWidget extends SettingWidget {

	private final KeybindSetting keybindSetting;
	private boolean listening;

	public KeybindSettingWidget(KeybindSetting setting, Theme theme) {
		super(setting, theme);
		this.keybindSetting = setting;
	}

	@Override
	public boolean isCapturingInput() {
		return listening;
	}

	@Override
	public void render(Renderer2D renderer, Font font, int mouseX, int mouseY, float partialTick) {
		int labelY = y + (height() - font.lineHeight) / 2;
		renderer.text(font, setting.name(), x, labelY, theme.textPrimary());

		String text = listening ? "..." : displayText();
		int pillWidth = Math.max(40, font.width(text) + 16);
		int pillX = x + width - pillWidth;
		int pillColor = listening ? theme.accentMuted(0.35) : theme.settingsBackground();
		renderer.roundedRect(pillX, y + 1, pillWidth, height() - 2, (height() - 2) / 2, pillColor);
		renderer.text(font, text, pillX + (pillWidth - font.width(text)) / 2, labelY, listening ? theme.accent() : theme.textSecondary());
	}

	private String displayText() {
		KeybindSetting.Bind bind = keybindSetting.get();
		return bind.isBound() ? KeyNames.name(bind.keyCode()) : "None";
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0 || !isHovered(event.x(), event.y())) {
			return false;
		}
		listening = true;
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!listening) {
			return false;
		}
		listening = false;
		KeybindSetting.Mode mode = keybindSetting.get().mode();
		if (event.key() == InputConstants.KEY_ESCAPE) {
			keybindSetting.set(KeybindSetting.Bind.unbound(mode));
		} else {
			keybindSetting.set(new KeybindSetting.Bind(event.key(), mode));
		}
		return true;
	}
}

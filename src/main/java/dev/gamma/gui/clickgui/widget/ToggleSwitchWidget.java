package dev.gamma.gui.clickgui.widget;

import dev.gamma.config.setting.BoolSetting;
import dev.gamma.gui.clickgui.Theme;
import dev.gamma.gui.clickgui.anim.Animated;
import dev.gamma.render.Renderer2D;
import dev.gamma.util.ColorUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;

/** A pill switch with a sliding thumb— not checkboxes. */
public final class ToggleSwitchWidget extends SettingWidget {

	private static final int TRACK_WIDTH = 30;
	private static final int TRACK_HEIGHT = 16;

	private final BoolSetting boolSetting;
	private final Animated thumb;

	public ToggleSwitchWidget(BoolSetting setting, Theme theme) {
		super(setting, theme);
		this.boolSetting = setting;
		this.thumb = Animated.of(setting.get() ? 1 : 0);
	}

	@Override
	public void render(Renderer2D renderer, Font font, int mouseX, int mouseY, float partialTick) {
		thumb.set(boolSetting.get() ? 1 : 0);
		double t = thumb.get();

		int labelWidth = width - TRACK_WIDTH - 8;
		renderer.text(font, setting.name(), x, y + (height() - font.lineHeight) / 2, theme.textPrimary());

		int trackX = x + labelWidth + 8;
		int trackY = y + (height() - TRACK_HEIGHT) / 2;
		int trackColor = ColorUtil.lerp(theme.trackOff(), theme.accent(), t);
		renderer.roundedRect(trackX, trackY, TRACK_WIDTH, TRACK_HEIGHT, TRACK_HEIGHT / 2, trackColor);

		int travel = TRACK_WIDTH - TRACK_HEIGHT;
		int thumbX = trackX + (int) Math.round(t * travel);
		renderer.circle(thumbX + TRACK_HEIGHT / 2, trackY + TRACK_HEIGHT / 2, TRACK_HEIGHT / 2 - 2, 0xFFF0F0F5);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0 || !isHovered(event.x(), event.y())) {
			return false;
		}
		boolSetting.set(!boolSetting.get());
		return true;
	}
}

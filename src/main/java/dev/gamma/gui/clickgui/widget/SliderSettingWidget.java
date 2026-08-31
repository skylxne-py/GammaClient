package dev.gamma.gui.clickgui.widget;

import dev.gamma.config.setting.DoubleSetting;
import dev.gamma.config.setting.IntSetting;
import dev.gamma.config.setting.Setting;
import dev.gamma.gui.clickgui.Theme;
import dev.gamma.gui.clickgui.anim.Animated;
import dev.gamma.render.Renderer2D;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleUnaryOperator;

/**
 * A smooth, click-and-drag slider for {@link DoubleSetting}/{@link IntSetting}. The thumb
 * scales up slightly on grab and the live value floats above it in a small label.
 */
public final class SliderSettingWidget extends SettingWidget {

	private static final int TRACK_HEIGHT = 4;
	private static final int THUMB_RADIUS = 5;
	private static final int THUMB_RADIUS_GRABBED = 7;

	private final double min;
	private final double max;
	private final DoubleSupplier getter;
	private final DoubleConsumer setter;
	private final DoubleUnaryOperator format;
	private final Animated thumbScale = Animated.of(1.0);
	private boolean dragging;

	private SliderSettingWidget(Setting<?> setting, Theme theme, double min, double max, DoubleSupplier getter, DoubleConsumer setter, DoubleUnaryOperator snap) {
		super(setting, theme);
		this.min = min;
		this.max = max;
		this.getter = getter;
		this.setter = setter;
		this.format = snap;
	}

	public static SliderSettingWidget forDouble(DoubleSetting setting, Theme theme) {
		return new SliderSettingWidget(setting, theme, setting.min(), setting.max(), setting::get, setting::set, v -> v);
	}

	public static SliderSettingWidget forInt(IntSetting setting, Theme theme) {
		return new SliderSettingWidget(setting, theme, setting.min(), setting.max(), setting::get,
				v -> setting.set((int) Math.round(v)), Math::round);
	}

	@Override
	public int height() {
		return 30;
	}

	@Override
	public void render(Renderer2D renderer, Font font, int mouseX, int mouseY, float partialTick) {
		boolean hovered = isHovered(mouseX, mouseY);
		thumbScale.set(dragging ? THUMB_RADIUS_GRABBED : THUMB_RADIUS);

		int labelY = y;
		renderer.text(font, setting.name(), x, labelY, theme.textPrimary());
		String valueText = formatValue();
		renderer.text(font, valueText, x + width - font.width(valueText), labelY, theme.accent());

		int trackY = y + font.lineHeight + 6;
		double t = progress();
		int filledWidth = (int) Math.round(width * t);

		renderer.roundedRect(x, trackY, width, TRACK_HEIGHT, TRACK_HEIGHT / 2, theme.trackOff());
		if (filledWidth > 0) {
			renderer.roundedRect(x, trackY, filledWidth, TRACK_HEIGHT, TRACK_HEIGHT / 2, theme.accent());
		}

		int thumbX = x + filledWidth;
		int thumbY = trackY + TRACK_HEIGHT / 2;
		int radius = (int) Math.round(thumbScale.get());
		int thumbColor = hovered || dragging ? theme.accentHover() : theme.accent();
		renderer.circle(thumbX, thumbY, radius, thumbColor);
	}

	private String formatValue() {
		double value = getter.getAsDouble();
		return value == Math.floor(value) && !Double.isInfinite(value)
				? Long.toString((long) value)
				: String.format("%.2f", value);
	}

	private double progress() {
		return max <= min ? 0 : (getter.getAsDouble() - min) / (max - min);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0 || !isHovered(event.x(), event.y())) {
			return false;
		}
		dragging = true;
		applyFromMouseX(event.x());
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (!dragging) {
			return false;
		}
		applyFromMouseX(event.x());
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (!dragging) {
			return false;
		}
		dragging = false;
		return true;
	}

	private void applyFromMouseX(double mouseX) {
		double t = width <= 0 ? 0 : Math.max(0, Math.min(1, (mouseX - x) / width));
		setter.accept(format.applyAsDouble(min + (max - min) * t));
	}
}

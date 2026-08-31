package dev.gamma.gui.clickgui.widget;

import dev.gamma.config.setting.Setting;
import dev.gamma.gui.clickgui.Theme;
import dev.gamma.render.Renderer2D;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * One row's worth of control for a single {@link Setting}, laid out by {@link
 * dev.gamma.gui.clickgui.ModernGuiScreen} in its settings pane. Deliberately not a
 * vanilla {@code AbstractWidget}/{@code GuiEventListener} — {@link
 * dev.gamma.gui.clickgui.ModernGuiScreen} dispatches input to these directly, the same
 * hand-rolled-dispatch approach every ClickGUI-style client uses, so a widget only needs to
 * implement the handful of callbacks it actually cares about.
 */
public abstract class SettingWidget {

	protected final Setting<?> setting;
	protected final Theme theme;
	protected int x;
	protected int y;
	protected int width;

	protected SettingWidget(Setting<?> setting, Theme theme) {
		this.setting = setting;
		this.theme = theme;
	}

	public final Setting<?> setting() {
		return setting;
	}

	public final void setBounds(int x, int y, int width) {
		this.x = x;
		this.y = y;
		this.width = width;
	}

	/** Height in pixels this widget needs at the current {@link #width} — most are fixed, list widgets vary. */
	public int height() {
		return 20;
	}

	public abstract void render(Renderer2D renderer, Font font, int mouseX, int mouseY, float partialTick);

	/**
	 * Anything that must float above the widgets below it — dropdowns, popovers.
	 * {@link dev.gamma.gui.clickgui.ModernGuiScreen} runs this for every widget after all of them
	 * have rendered, so drawing here escapes the paint order that would otherwise bury it under the
	 * next setting down. Bounds are whatever {@link #render} left them as.
	 */
	public void renderOverlay(Renderer2D renderer, Font font, int mouseX, int mouseY, float partialTick) {
	}

	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return false;
	}

	public boolean mouseReleased(MouseButtonEvent event) {
		return false;
	}

	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		return false;
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		return false;
	}

	public boolean keyPressed(KeyEvent event) {
		return false;
	}

	public boolean charTyped(CharacterEvent event) {
		return false;
	}

	/** Whether this widget currently wants exclusive keyboard input (text fields, keybind capture). */
	public boolean isCapturingInput() {
		return false;
	}

	protected boolean isHovered(double mouseX, double mouseY) {
		return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height();
	}
}

package dev.gamma.gui.clickgui;

import dev.gamma.config.GammaSettings;
import dev.gamma.config.setting.ColorSetting;
import dev.gamma.config.setting.Setting;
import dev.gamma.core.Module;
import dev.gamma.core.ModuleRegistry;
import dev.gamma.gui.GammaLogo;
import dev.gamma.gui.GammaScreen;
import dev.gamma.gui.clickgui.anim.Animated;
import dev.gamma.gui.clickgui.anim.Easing;
import dev.gamma.gui.clickgui.widget.SettingWidget;
import dev.gamma.render.Renderer2D;
import dev.gamma.util.ColorUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE;

/**
 * General client settings, reached from the gear in the ClickGUI header.
 *
 * <p>Everything here is true of Gamma rather than of the game: whether it talks in chat, which key
 * opens the menu, whether the HUD draws at all. Those had nowhere to live — the category tabs are
 * for features, and putting client plumbing among them means scrolling past it every time you look
 * for a module.
 *
 * <h2>Two sources, one list</h2>
 *
 * <p>The first section is {@link GammaSettings}, which is a bag of {@link Setting} fields with no
 * module attached. The rest are the client-level modules ({@link Module#isClientLevel()}), each
 * shown as a title row with its own enable switch followed by its settings. {@code DiscordRPC} is
 * the one that motivated this: it is an integration with the client rather than a feature of the
 * game, so it belongs here — but it still has real connect/disconnect work and settings to persist,
 * which is a module's job. Listing it here and hiding it from its category is the whole change; the
 * module is untouched.
 *
 * <p>Both kinds render through the same {@link WidgetFactory}, so a setting looks and behaves the
 * same wherever it is declared.
 *
 * <h2>Saving</h2>
 *
 * <p>{@link GammaSettings} is written when this screen closes rather than on every change, because
 * {@link Setting} has no change hook and this is the only place its values can move. Module settings
 * need nothing here — they ride the profile the way they always have.
 */
public final class SettingsScreen extends GammaScreen implements ColorPickerHost, GammaGuiScreen {

	private static final int MAX_WIDTH = 420;
	private static final int MAX_HEIGHT = 440;
	private static final int MARGIN = 40;
	private static final int RADIUS = 14;
	private static final int HEADER_HEIGHT = 52;
	/** Wordmark height in the header; its width follows from the artwork's aspect. */
	private static final int LOGO_HEIGHT = 18;
	private static final int PADDING = 18;
	private static final int SETTING_GAP = 4;
	private static final int SECTION_GAP = 16;
	private static final int TITLE_HEIGHT = 20;
	private static final int SCROLLBAR_WIDTH = 3;
	private static final int ENTRY_RISE = 14;
	private static final int PICKER_GAP = 8;
	private static final int SWITCH_WIDTH = 24;
	private static final int SWITCH_HEIGHT = 12;

	private final GammaSettings settings;
	private final ModuleRegistry registry;
	private final Theme theme;
	private final Screen parent;

	private final Animated entry = new Animated(0, Easing.EXPO_OUT, 260);
	private final Map<Module, Animated> switchAnimations = new HashMap<>();
	/** Where each module's switch was last drawn, so a click can be tested against what is on screen rather than re-derived through the scroll offset. */
	private final Map<Module, int[]> switchBounds = new HashMap<>();

	private final List<Section> sections = new ArrayList<>();
	private ColorPickerPopup activePopup;
	private double scroll;
	private int windowX;
	private int windowY;
	private int windowWidth;
	private int windowHeight;

	/** A titled group. {@code module} is null for the client section and non-null when the title row toggles a module. */
	private record Section(String title, String description, Module module, List<SettingWidget> widgets) {
	}

	public SettingsScreen(GammaSettings settings, ModuleRegistry registry, Theme theme, Screen parent) {
		super(Component.literal("Gamma Settings"));
		this.settings = settings;
		this.registry = registry;
		this.theme = theme;
		this.parent = parent;
	}

	@Override
	protected void init() {
		windowWidth = Math.min(MAX_WIDTH, width - MARGIN * 2);
		windowHeight = Math.min(MAX_HEIGHT, height - MARGIN * 2);
		windowX = (width - windowWidth) / 2;
		windowY = (height - windowHeight) / 2;

		sections.clear();
		sections.add(new Section("Client", "How Gamma itself behaves.", null, build(settings.settings())));
		for (Module module : registry.clientLevel()) {
			sections.add(new Section(module.name(), module.description(), module, build(module.settings())));
		}

		entry.snapTo(0);
		entry.set(1);
	}

	private List<SettingWidget> build(List<Setting<?>> declared) {
		List<SettingWidget> widgets = new ArrayList<>();
		for (Setting<?> setting : declared) {
			widgets.add(WidgetFactory.create(setting, theme, this));
		}
		return widgets;
	}

	// -- rendering -----------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		Renderer2D renderer = new Renderer2D(extractor);
		renderer.setTextShadow(false);

		double progress = entry.get();
		int wx = windowX;
		int wy = windowY + (int) Math.round((1.0 - progress) * ENTRY_RISE);

		renderer.fill(0, 0, width, height, ColorUtil.scaleAlpha(0xCC05060A, progress));
		renderer.blurBackdrop();
		renderer.roundedRect(wx + 4, wy + 6, windowWidth, windowHeight, RADIUS, fade(theme.shadow(), progress));
		renderer.roundedRect(wx, wy, windowWidth, windowHeight, RADIUS, fade(theme.panelBackground(), progress));

		GammaLogo.render(renderer, wx + PADDING, wy + (HEADER_HEIGHT - LOGO_HEIGHT) / 2, LOGO_HEIGHT, GammaLogo.fade(progress));
		renderer.text(font, "Settings", wx + PADDING + GammaLogo.widthFor(LOGO_HEIGHT) + 10, wy + (HEADER_HEIGHT - font.lineHeight) / 2, fade(theme.textPrimary(), progress));
		renderer.text(font, "Esc to go back", wx + windowWidth - PADDING - font.width("Esc to go back"),
				wy + (HEADER_HEIGHT - font.lineHeight) / 2, fade(theme.textSecondary(), progress));
		renderer.fill(wx + PADDING, wy + HEADER_HEIGHT - 1, wx + windowWidth - PADDING, wy + HEADER_HEIGHT, fade(theme.divider(), progress));

		int contentX = wx + PADDING;
		int contentTop = wy + HEADER_HEIGHT + 8;
		int contentBottom = wy + windowHeight - PADDING;
		int contentWidth = windowWidth - PADDING * 2;

		int contentHeight = measureContent();
		int viewport = contentBottom - contentTop;
		int maxScroll = Math.max(0, contentHeight - viewport);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		int widgetWidth = contentWidth - (maxScroll > 0 ? SCROLLBAR_WIDTH + 6 : 0);

		switchBounds.clear();
		renderer.pushScissor(contentX, contentTop, contentX + contentWidth + 2, contentBottom);
		int cursor = contentTop - (int) Math.round(scroll);
		for (Section section : sections) {
			cursor = renderSection(renderer, section, contentX, cursor, widgetWidth, mouseX, mouseY, partialTick, progress);
		}
		renderer.popScissor();

		// After the scissor: dropdowns and popovers have to escape the scrolled viewport, the same
		// way they escape the paint order in the main menu's settings pane.
		for (Section section : sections) {
			for (SettingWidget widget : section.widgets()) {
				if (widget.setting().isVisible()) {
					widget.renderOverlay(renderer, font, mouseX, mouseY, partialTick);
				}
			}
		}

		if (maxScroll > 0) {
			int trackHeight = viewport - 4;
			int thumbHeight = Math.max(16, (int) Math.round(trackHeight * (double) viewport / contentHeight));
			int thumbY = contentTop + 2 + (int) Math.round((trackHeight - thumbHeight) * (scroll / maxScroll));
			renderer.roundedRect(contentX + contentWidth - SCROLLBAR_WIDTH, thumbY, SCROLLBAR_WIDTH, thumbHeight,
					SCROLLBAR_WIDTH / 2, fade(theme.trackOff(), progress));
		}

		if (activePopup != null) {
			activePopup.clampToScreen(width, height);
			activePopup.render(renderer, font);
		}
	}

	/** Draws one section and returns the y just past it. */
	private int renderSection(Renderer2D renderer, Section section, int x, int y, int widgetWidth,
			int mouseX, int mouseY, float partialTick, double progress) {
		renderer.text(font, section.title(), x, y + (TITLE_HEIGHT - font.lineHeight) / 2, fade(theme.textPrimary(), progress));
		if (section.module() != null) {
			int switchX = x + widgetWidth - SWITCH_WIDTH;
			int switchY = y + (TITLE_HEIGHT - SWITCH_HEIGHT) / 2;
			drawSwitch(renderer, section.module(), switchX, switchY, progress);
			switchBounds.put(section.module(), new int[]{switchX, switchY, SWITCH_WIDTH, SWITCH_HEIGHT});
		}
		int cursor = y + TITLE_HEIGHT;
		// Trimmed rather than wrapped so it stays exactly one line: measureContent() has to agree with
		// this to the pixel or the scroll range drifts, and a wrapped line count is one more thing for
		// the two to disagree about.
		renderer.text(font, trim(section.description(), widgetWidth), x, cursor, fade(theme.textSecondary(), progress));
		cursor += font.lineHeight + 6;

		for (SettingWidget widget : section.widgets()) {
			if (!widget.setting().isVisible()) {
				continue;
			}
			widget.setBounds(x, cursor, widgetWidth);
			widget.render(renderer, font, mouseX, mouseY, partialTick);
			cursor += widget.height() + SETTING_GAP;
		}
		return cursor + SECTION_GAP;
	}

	/** Same control as the module list's, for the same reason: the movement is what says the two states are one switch. */
	private void drawSwitch(Renderer2D renderer, Module module, int x, int y, double progress) {
		Animated animation = switchAnimations.computeIfAbsent(module, m -> Animated.of(m.isEnabled() ? 1 : 0));
		animation.set(module.isEnabled() ? 1 : 0);
		double on = animation.get();

		renderer.roundedRect(x, y, SWITCH_WIDTH, SWITCH_HEIGHT, SWITCH_HEIGHT / 2,
				fade(ColorUtil.lerp(theme.trackOff(), theme.accent(), on), progress));
		int knobX = x + 2 + (int) Math.round(on * (SWITCH_WIDTH - SWITCH_HEIGHT));
		renderer.circle(knobX + (SWITCH_HEIGHT - 4) / 2, y + SWITCH_HEIGHT / 2, (SWITCH_HEIGHT - 4) / 2, fade(0xFFF4F4F8, progress));
	}

	private int measureContent() {
		int total = 0;
		for (Section section : sections) {
			total += TITLE_HEIGHT + font.lineHeight + 6;
			for (SettingWidget widget : section.widgets()) {
				if (widget.setting().isVisible()) {
					total += widget.height() + SETTING_GAP;
				}
			}
			total += SECTION_GAP;
		}
		return total;
	}

	/** The longest prefix of {@code text} that fits {@code maxWidth}, with an ellipsis if it had to cut. */
	private String trim(String text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return text;
		}
		int budget = maxWidth - font.width("...");
		int end = text.length();
		while (end > 0 && font.width(text.substring(0, end)) > budget) {
			end--;
		}
		return text.substring(0, end).stripTrailing() + "...";
	}

	private int fade(int color, double amount) {
		return ColorUtil.scaleAlpha(color, Math.max(0.0, Math.min(1.0, amount)));
	}

	// -- input ---------------------------------------------------------------

	@Override
	public boolean isCapturingTextInput() {
		if (activePopup != null && activePopup.isCapturingInput()) {
			return true;
		}
		return anyWidget(SettingWidget::isCapturingInput);
	}

	private boolean anyWidget(java.util.function.Predicate<SettingWidget> test) {
		for (Section section : sections) {
			for (SettingWidget widget : section.widgets()) {
				if (test.test(widget)) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (activePopup != null) {
			if (activePopup.mouseClicked(event, doubleClick)) {
				return true;
			}
			if (!activePopup.contains(event.x(), event.y())) {
				activePopup = null;
			}
			return true;
		}
		for (Map.Entry<Module, int[]> entry : switchBounds.entrySet()) {
			int[] box = entry.getValue();
			if (event.x() >= box[0] && event.x() < box[0] + box[2] && event.y() >= box[1] && event.y() < box[1] + box[3]) {
				entry.getKey().toggle();
				return true;
			}
		}
		return anyWidget(widget -> widget.setting().isVisible() && widget.mouseClicked(event, doubleClick));
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (activePopup != null && activePopup.mouseReleased(event)) {
			return true;
		}
		return anyWidget(widget -> widget.mouseReleased(event));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (activePopup != null && activePopup.mouseDragged(event, dragX, dragY)) {
			return true;
		}
		return anyWidget(widget -> widget.mouseDragged(event, dragX, dragY));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (anyWidget(widget -> widget.setting().isVisible() && widget.mouseScrolled(mouseX, mouseY, scrollX, scrollY))) {
			return true;
		}
		scroll = Math.max(0, scroll - scrollY * 18);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (activePopup != null && activePopup.keyPressed(event)) {
			return true;
		}
		if (anyWidget(widget -> widget.setting().isVisible() && widget.keyPressed(event))) {
			return true;
		}
		if (event.key() == KEY_ESCAPE) {
			if (activePopup != null) {
				activePopup = null;
				return true;
			}
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (activePopup != null && activePopup.charTyped(event)) {
			return true;
		}
		if (anyWidget(widget -> widget.setting().isVisible() && widget.charTyped(event))) {
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().gui.setScreen(parent);
	}

	/**
	 * Saving hangs off {@code removed} rather than {@link #onClose()} because {@code onClose} is only
	 * one of the ways out. The menu key closes this screen through {@code setScreen(null)} — which
	 * calls {@code removed} and never {@code onClose} — and so does a disconnect. {@code removed} is
	 * the one callback every exit passes through, so it is the only place the save can't be skipped.
	 */
	@Override
	public void removed() {
		settings.requestSave();
	}

	@Override
	public void openColorPicker(ColorSetting setting, int swatchX, int swatchY, int swatchWidth, int swatchHeight) {
		int outerW = ColorPickerPopup.outerWidth();
		int outerH = ColorPickerPopup.outerHeight();
		int paintedX = swatchX - PICKER_GAP - outerW;
		if (paintedX < PICKER_GAP) {
			paintedX = swatchX + swatchWidth + PICKER_GAP;
		}
		int paintedY = Math.max(PICKER_GAP, Math.min(swatchY + swatchHeight / 2 - outerH / 2, height - outerH - PICKER_GAP));
		int padding = ColorPickerPopup.padding();
		this.activePopup = new ColorPickerPopup(setting.get(), setting::set, theme, paintedX + padding, paintedY + padding);
	}
}

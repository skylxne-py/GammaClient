package dev.gamma.gui.clickgui;

import dev.gamma.config.ConfigManager;
import dev.gamma.config.GammaSettings;
import dev.gamma.config.setting.ColorSetting;
import dev.gamma.config.setting.Setting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.core.ModuleRegistry;
import dev.gamma.gui.GammaLogo;
import dev.gamma.gui.GammaScreen;
import dev.gamma.gui.clickgui.anim.Animated;
import dev.gamma.gui.clickgui.anim.Easing;
import dev.gamma.gui.clickgui.widget.SettingWidget;
import dev.gamma.gui.hud.HudEditorScreen;
import dev.gamma.gui.hud.HudManager;
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
 * The Gamma menu: one centred window laid out like an application.
 *
 * <h2>Why it replaced the panel GUI</h2>
 *
 * <p>The first attempt at a modern look retuned the old panel GUI's padding, radii and easing,
 * which changed how it looked without changing what it was — still a grid of floating, individually
 * draggable panels. The difference is structural: one surface instead of many, a fixed place for
 * everything, and no window management to do by hand. That could not be reached by adjusting
 * constants on a design built around draggable panels, so it became its own screen, and once it
 * worked there was no reason to keep maintaining two.
 *
 * <h2>Layout</h2>
 *
 * <pre>
 *   header    title, search, and the screen-level buttons
 *   rail      categories, with an indicator that slides between them
 *   list      the selected category's modules, name plus a switch
 *   detail    the highlighted module's description and settings
 * </pre>
 *
 * <p>Three columns rather than expanding rows in place: settings then have somewhere permanent to
 * live, so opening one never reflows the list underneath the pointer — the specific thing that
 * makes a long panel of expandable rows feel unsteady.
 *
 * <h2>Animation</h2>
 *
 * <p>Opening slides the window up a few pixels while fading in; the rail indicator glides between
 * categories; the detail pane fades when its module changes. The open animation moves the window's
 * <em>layout</em> position rather than scaling a transform around it, which keeps hit-testing
 * exactly aligned with what is drawn for the whole of it — a scaled transform would leave clicks
 * landing somewhere other than where the buttons appear until it settled.
 */
public final class ModernGuiScreen extends GammaScreen implements ColorPickerHost, GammaGuiScreen {

	private static final int MAX_WIDTH = 760;
	private static final int MAX_HEIGHT = 440;
	private static final int MARGIN = 40;
	private static final int RADIUS = 14;
	private static final int HEADER_HEIGHT = 52;
	/** Wordmark height in the header; its width follows from the artwork's aspect. */
	private static final int LOGO_HEIGHT = 18;
	private static final int RAIL_WIDTH = 132;

	/** Author credit, bottom-left of the ClickGUI window. */
	private static final String WATERMARK = "by Skylxne";
	private static final int DETAIL_WIDTH = 252;
	private static final int ROW_HEIGHT = 30;
	private static final int RAIL_ROW_HEIGHT = 32;
	private static final int PADDING = 18;
	private static final int SETTING_GAP = 4;
	private static final int SCROLLBAR_WIDTH = 3;
	/** How far the window travels on open, in pixels. */
	private static final int ENTRY_RISE = 14;
	private static final int PICKER_GAP = 8;
	private static final int CONTROL_RADIUS = 8;
	/** Square, so an icon button matches the height of the lettered ones beside it. */
	private static final int ICON_BUTTON_SIZE = 20;

	private final ModuleRegistry registry;
	private final Theme theme;
	private final GuiConfig guiConfig;
	private final HudManager hudManager;
	private final ConfigManager configManager;
	private final GammaSettings settings;
	private final SearchBar searchBar;

	private final Animated entry = new Animated(0, Easing.EXPO_OUT, 260);
	private final Animated railIndicator = new Animated(0, Easing.EXPO_OUT, 220);
	private final Animated detailFade = new Animated(1, Easing.EXPO_OUT, 180);
	/** One per module, so a switch slides between states instead of snapping. Built on first draw. */
	private final Map<Module, Animated> switchAnimations = new HashMap<>();

	private Category selectedCategory;
	private Module selectedModule;
	private List<SettingWidget> detailWidgets = List.of();
	private ColorPickerPopup activePopup;

	private double listScroll;
	private double detailScroll;
	private int windowX;
	private int windowY;
	private int windowWidth;
	private int windowHeight;
	private int hudButtonX;
	private int hudButtonWidth;
	private int configsButtonX;
	private int configsButtonWidth;
	private int settingsButtonX;
	private int accentSwatchX;
	private int accentSwatchSize;

	public ModernGuiScreen(ModuleRegistry registry, Theme theme, GuiConfig guiConfig, HudManager hudManager, ConfigManager configManager, GammaSettings settings) {
		super(Component.literal("Gamma"));
		this.registry = registry;
		this.theme = theme;
		this.guiConfig = guiConfig;
		this.hudManager = hudManager;
		this.configManager = configManager;
		this.settings = settings;
		this.searchBar = new SearchBar(theme);
		// Reopening the menu should land where it was left, which means the tab is a persisted
		// preference rather than screen-local state -- the screen itself is rebuilt every open.
		this.selectedCategory = guiConfig.lastCategory();
	}

	@Override
	protected void init() {
		windowWidth = Math.min(MAX_WIDTH, width - MARGIN * 2);
		windowHeight = Math.min(MAX_HEIGHT, height - MARGIN * 2);
		windowX = (width - windowWidth) / 2;
		windowY = (height - windowHeight) / 2;

		int right = windowX + windowWidth - PADDING;
		hudButtonWidth = font.width("HUD") + 18;
		configsButtonWidth = font.width("CONFIGS") + 18;
		accentSwatchSize = 20;
		accentSwatchX = right - accentSwatchSize;
		settingsButtonX = accentSwatchX - 8 - ICON_BUTTON_SIZE;
		configsButtonX = settingsButtonX - 8 - configsButtonWidth;
		hudButtonX = configsButtonX - 8 - hudButtonWidth;
		searchBar.setPosition(windowX + RAIL_WIDTH + PADDING, windowY + (HEADER_HEIGHT - SearchBar.HEIGHT) / 2);

		entry.snapTo(0);
		entry.set(1);
		if (selectedModule == null) {
			List<Module> modules = visibleModules();
			select(modules.isEmpty() ? null : modules.getFirst());
		}
		railIndicator.snapTo(categoryIndex(selectedCategory) * RAIL_ROW_HEIGHT);
	}

	// -- rendering -----------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		Renderer2D renderer = new Renderer2D(extractor);
		renderer.setTextShadow(false);

		double progress = entry.get();
		int rise = (int) Math.round((1.0 - progress) * ENTRY_RISE);
		int wx = windowX;
		int wy = windowY + rise;

		renderer.fill(0, 0, width, height, ColorUtil.scaleAlpha(0xCC05060A, progress));
		renderer.blurBackdrop();

		renderer.roundedRect(wx + 4, wy + 6, windowWidth, windowHeight, RADIUS, fade(theme.shadow(), progress));
		renderer.roundedRect(wx, wy, windowWidth, windowHeight, RADIUS, fade(theme.panelBackground(), progress));

		renderHeader(renderer, wx, wy, mouseX, mouseY, progress);
		renderRail(renderer, wx, wy, mouseX, mouseY, progress);
		renderList(renderer, wx, wy, mouseX, mouseY, partialTick, progress);
		renderDetail(renderer, wx, wy, mouseX, mouseY, partialTick, progress);
		renderWatermark(renderer, wx, wy, progress);

		if (activePopup != null) {
			activePopup.clampToScreen(width, height);
			activePopup.render(renderer, font);
		}
	}

	/**
	 * Credit line in the window's bottom-left, under the category rail.
	 *
	 * <p>Drawn after the panes rather than with the rail so it cannot be clipped by a list that
	 * scrolls past it, and in the secondary text colour so it reads as a signature rather than as
	 * another control someone might try to click.
	 */
	private void renderWatermark(Renderer2D renderer, int wx, int wy, double progress) {
		int x = wx + PADDING + 6;
		int y = wy + windowHeight - PADDING - font.lineHeight;
		renderer.text(font, WATERMARK, x, y, fade(theme.textSecondary(), progress));
	}

	private void renderHeader(Renderer2D renderer, int wx, int wy, int mouseX, int mouseY, double progress) {
		// The wordmark already reads "GammaClient", so there is no title text beside it -- that was
		// saying the same thing twice on a screen whose whole job is already unambiguous.
		GammaLogo.render(renderer, wx + PADDING, wy + (HEADER_HEIGHT - LOGO_HEIGHT) / 2, LOGO_HEIGHT, GammaLogo.fade(progress));

		// Re-anchored every frame because the whole window rises during the open animation, and the
		// stored position is what click hit-testing reads -- so the two stay in step throughout it.
		int searchY = wy + (HEADER_HEIGHT - SearchBar.HEIGHT) / 2;
		searchBar.setPosition(searchBar.x(), searchY);
		renderSearch(renderer, searchY, progress);

		int buttonY = wy + (HEADER_HEIGHT - 20) / 2;
		renderButton(renderer, hudButtonX, buttonY, hudButtonWidth, "HUD", mouseX, mouseY, progress, false);
		renderButton(renderer, configsButtonX, buttonY, configsButtonWidth, "CONFIGS", mouseX, mouseY, progress, false);
		renderSettingsButton(renderer, settingsButtonX, buttonY, mouseX, mouseY, progress);
		renderer.roundedRect(accentSwatchX, buttonY, accentSwatchSize, 20, 8, fade(theme.accent(), progress));

		renderer.fill(wx + PADDING, wy + HEADER_HEIGHT - 1, wx + windowWidth - PADDING, wy + HEADER_HEIGHT, fade(theme.divider(), progress));
	}

	/** The search field, drawn flat here rather than through {@link SearchBar#render} so it matches this window. */
	private void renderSearch(Renderer2D renderer, int y, double progress) {
		int x = searchBar.x();
		int fieldWidth = SearchBar.WIDTH;
		renderer.roundedRect(x, y, fieldWidth, SearchBar.HEIGHT, SearchBar.HEIGHT / 2, fade(theme.settingsBackground(), progress));
		if (searchBar.isFocused()) {
			renderer.roundedRectOutline(x, y, fieldWidth, SearchBar.HEIGHT, SearchBar.HEIGHT / 2, 1f, fade(theme.accent(), progress));
		}
		String query = searchBar.query();
		String text = query.isEmpty() ? "Search" : query;
		int color = query.isEmpty() ? theme.textSecondary() : theme.textPrimary();
		renderer.text(font, text, x + 12, y + (SearchBar.HEIGHT - font.lineHeight) / 2, fade(color, progress));
	}

	private void renderButton(Renderer2D renderer, int x, int y, int buttonWidth, String label, int mouseX, int mouseY, double progress, boolean accented) {
		boolean hovered = mouseX >= x && mouseX < x + buttonWidth && mouseY >= y && mouseY < y + 20;
		renderer.roundedRect(x, y, buttonWidth, 20, 8, fade(hovered ? theme.rowHoverBackground() : theme.settingsBackground(), progress));
		int color = accented ? theme.accent() : theme.textPrimary();
		renderer.text(font, label, x + (buttonWidth - font.width(label)) / 2, y + (20 - font.lineHeight) / 2, fade(color, progress));
	}

	/**
	 * Three sliders, not a gear. An icon rather than another word, because this button is the one
	 * that isn't about the modules on screen and a fourth all-caps label beside HUD and CONFIGS would
	 * read as a fourth of the same kind of thing — but a gear does not survive being twelve pixels
	 * across. Teeth at that size are a pixel or two each and the centre has to be punched out of a
	 * translucent button background, so it came out as a blob with a smudge in it. Sliders are three
	 * straight lines and three dots: they stay crisp at any GUI scale, need nothing punched, and say
	 * the same thing — arguably better, since what the button opens is a list of settings.
	 */
	private void renderSettingsButton(Renderer2D renderer, int x, int y, int mouseX, int mouseY, double progress) {
		boolean hovered = mouseX >= x && mouseX < x + ICON_BUTTON_SIZE && mouseY >= y && mouseY < y + ICON_BUTTON_SIZE;
		renderer.roundedRect(x, y, ICON_BUTTON_SIZE, ICON_BUTTON_SIZE, CONTROL_RADIUS,
				fade(hovered ? theme.rowHoverBackground() : theme.settingsBackground(), progress));

		int cx = x + ICON_BUTTON_SIZE / 2;
		int cy = y + ICON_BUTTON_SIZE / 2;
		int knobColor = fade(hovered ? theme.accent() : theme.textPrimary(), progress);
		// Dimmer track behind brighter knobs, so the knobs read as sitting on the rails rather than
		// the whole thing reading as one shape.
		int trackColor = ColorUtil.scaleAlpha(knobColor, 0.45);
		int left = cx - 6;
		int right = cx + 6;
		// Staggered on purpose: three knobs in a column would look like a single vertical bar.
		int[] rowY = {cy - 5, cy, cy + 5};
		double[] knobAt = {0.28, 0.72, 0.46};
		for (int i = 0; i < rowY.length; i++) {
			renderer.fill(left, rowY[i], right, rowY[i] + 1, trackColor);
			renderer.circle(left + (int) Math.round((right - left) * knobAt[i]), rowY[i], 2, knobColor);
		}
	}

	private void renderRail(Renderer2D renderer, int wx, int wy, int mouseX, int mouseY, double progress) {
		int railX = wx + PADDING;
		int railTop = wy + HEADER_HEIGHT + PADDING - 6;
		Category[] categories = Category.values();

		// The indicator is a rounded plate that slides, rather than a highlight that appears on the
		// new row and vanishes from the old: the movement is what tells you the two are the same
		// control in different states.
		railIndicator.set(categoryIndex(selectedCategory) * (double) RAIL_ROW_HEIGHT);
		int indicatorY = railTop + (int) Math.round(railIndicator.get());
		if (searchBar.query().isEmpty()) {
			renderer.roundedRect(railX - 6, indicatorY, RAIL_WIDTH - PADDING, RAIL_ROW_HEIGHT - 4, 8,
					fade(ColorUtil.scaleAlpha(theme.accent(), 0.16), progress));
			renderer.roundedRect(railX - 6, indicatorY + 6, 2, RAIL_ROW_HEIGHT - 16, 1, fade(theme.accent(), progress));
		}

		for (int i = 0; i < categories.length; i++) {
			int rowY = railTop + i * RAIL_ROW_HEIGHT;
			boolean hovered = mouseX >= railX - 6 && mouseX < railX + RAIL_WIDTH - PADDING - 6
					&& mouseY >= rowY && mouseY < rowY + RAIL_ROW_HEIGHT - 4;
			boolean active = categories[i] == selectedCategory && searchBar.query().isEmpty();
			int color = active ? theme.textPrimary() : hovered ? theme.textPrimary() : theme.textSecondary();
			renderer.text(font, categories[i].displayName(), railX + 6, rowY + (RAIL_ROW_HEIGHT - 4 - font.lineHeight) / 2, fade(color, progress));
		}

		renderer.fill(wx + RAIL_WIDTH, wy + HEADER_HEIGHT + 8, wx + RAIL_WIDTH + 1, wy + windowHeight - 8, fade(theme.divider(), progress));
	}

	private void renderList(Renderer2D renderer, int wx, int wy, int mouseX, int mouseY, float partialTick, double progress) {
		int listX = wx + RAIL_WIDTH + PADDING;
		int listTop = wy + HEADER_HEIGHT + PADDING - 6;
		int listWidth = windowWidth - RAIL_WIDTH - DETAIL_WIDTH - PADDING * 2;
		int listBottom = wy + windowHeight - PADDING;
		List<Module> modules = visibleModules();

		listScroll = Math.max(0, Math.min(listScroll, Math.max(0, modules.size() * ROW_HEIGHT - (listBottom - listTop))));
		renderer.pushScissor(listX, listTop, listX + listWidth, listBottom);
		int cursor = listTop - (int) Math.round(listScroll);
		String query = searchBar.query();
		for (Module module : modules) {
			if (cursor + ROW_HEIGHT >= listTop && cursor <= listBottom) {
				renderModuleRow(renderer, module, listX, cursor, listWidth, mouseX, mouseY, progress, query);
			}
			cursor += ROW_HEIGHT;
		}
		renderer.popScissor();

		if (modules.isEmpty()) {
			renderer.text(font, "Nothing matches", listX + 4, listTop + 6, fade(theme.textSecondary(), progress));
		}
		renderer.fill(wx + windowWidth - DETAIL_WIDTH - PADDING, wy + HEADER_HEIGHT + 8,
				wx + windowWidth - DETAIL_WIDTH - PADDING + 1, wy + windowHeight - 8, fade(theme.divider(), progress));
	}

	private void renderModuleRow(Renderer2D renderer, Module module, int x, int y, int rowWidth, int mouseX, int mouseY, double progress, String query) {
		boolean hovered = mouseX >= x && mouseX < x + rowWidth && mouseY >= y && mouseY < y + ROW_HEIGHT;
		boolean selected = module == selectedModule;
		if (selected) {
			renderer.roundedRect(x - 6, y + 1, rowWidth + 6, ROW_HEIGHT - 3, 8, fade(theme.rowHoverBackground(), progress));
		} else if (hovered) {
			renderer.roundedRect(x - 6, y + 1, rowWidth + 6, ROW_HEIGHT - 3, 8, fade(ColorUtil.scaleAlpha(theme.rowHoverBackground(), 0.5), progress));
		}

		int textY = y + (ROW_HEIGHT - font.lineHeight) / 2;
		int[] matches = query.isEmpty() ? new int[0] : FuzzyMatch.match(query, module.name());
		TextHighlight.render(renderer, font, module.name(), matches, x, textY,
				fade(theme.textPrimary(), progress), fade(theme.accent(), progress));

		drawSwitch(renderer, module, x + rowWidth - 30, y + (ROW_HEIGHT - 12) / 2, progress);
	}

	/**
	 * The enable switch: the track colour crossfades and the knob travels, both driven by one
	 * {@link Animated} per module. Kept per module rather than per row because rows are recycled as
	 * the list scrolls and filters — animating by row index would make a toggle appear to slide
	 * whenever a scroll moved a different module into that position.
	 */
	private void drawSwitch(Renderer2D renderer, Module module, int x, int y, double progress) {
		int trackWidth = 24;
		int trackHeight = 12;
		Animated animation = switchAnimations.computeIfAbsent(module, m -> Animated.of(m.isEnabled() ? 1 : 0));
		animation.set(module.isEnabled() ? 1 : 0);
		double on = animation.get();

		renderer.roundedRect(x, y, trackWidth, trackHeight, trackHeight / 2,
				fade(ColorUtil.lerp(theme.trackOff(), theme.accent(), on), progress));
		int knobX = x + 2 + (int) Math.round(on * (trackWidth - trackHeight));
		renderer.circle(knobX + (trackHeight - 4) / 2, y + trackHeight / 2, (trackHeight - 4) / 2, fade(0xFFF4F4F8, progress));
	}

	private void renderDetail(Renderer2D renderer, int wx, int wy, int mouseX, int mouseY, float partialTick, double progress) {
		int detailX = wx + windowWidth - DETAIL_WIDTH;
		int detailTop = wy + HEADER_HEIGHT + PADDING - 6;
		int detailBottom = wy + windowHeight - PADDING;
		double fade = detailFade.get() * progress;

		if (selectedModule == null) {
			renderer.text(font, "Select a module", detailX, detailTop + 6, fade(theme.textSecondary(), fade));
			return;
		}

		renderer.text(font, selectedModule.name(), detailX, detailTop, fade(theme.textPrimary(), fade));
		int descriptionBottom = drawWrapped(renderer, selectedModule.description(), detailX, detailTop + 14,
				DETAIL_WIDTH - PADDING, fade(theme.textSecondary(), fade));

		int settingsTop = descriptionBottom + 10;
		renderer.fill(detailX, settingsTop - 6, detailX + DETAIL_WIDTH - PADDING, settingsTop - 5, fade(theme.divider(), fade));

		List<SettingWidget> visible = detailWidgets.stream().filter(w -> w.setting().isVisible()).toList();
		int contentHeight = 0;
		for (SettingWidget widget : visible) {
			contentHeight += widget.height() + SETTING_GAP;
		}
		int viewport = detailBottom - settingsTop;
		int maxScroll = Math.max(0, contentHeight - viewport);
		detailScroll = Math.max(0, Math.min(detailScroll, maxScroll));

		renderer.pushScissor(detailX, settingsTop, detailX + DETAIL_WIDTH - PADDING + 2, detailBottom);
		int cursor = settingsTop - (int) Math.round(detailScroll);
		for (SettingWidget widget : visible) {
			widget.setBounds(detailX, cursor, DETAIL_WIDTH - PADDING - (maxScroll > 0 ? SCROLLBAR_WIDTH + 6 : 0));
			widget.render(renderer, font, mouseX, mouseY, partialTick);
			cursor += widget.height() + SETTING_GAP;
		}
		renderer.popScissor();

		for (SettingWidget widget : visible) {
			widget.renderOverlay(renderer, font, mouseX, mouseY, partialTick);
		}

		if (maxScroll > 0) {
			int trackHeight = viewport - 4;
			int thumbHeight = Math.max(16, (int) Math.round(trackHeight * (double) viewport / contentHeight));
			int thumbY = settingsTop + 2 + (int) Math.round((trackHeight - thumbHeight) * (detailScroll / maxScroll));
			renderer.roundedRect(detailX + DETAIL_WIDTH - PADDING - SCROLLBAR_WIDTH, thumbY, SCROLLBAR_WIDTH, thumbHeight,
					SCROLLBAR_WIDTH / 2, fade(theme.trackOff(), fade));
		}
	}

	/** Draws {@code text} wrapped to {@code maxWidth} and returns the y just past the last line. */
	private int drawWrapped(Renderer2D renderer, String text, int x, int y, int maxWidth, int color) {
		int lineY = y;
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (font.width(candidate) > maxWidth && !line.isEmpty()) {
				renderer.text(font, line.toString(), x, lineY, color);
				lineY += font.lineHeight + 1;
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(candidate);
			}
		}
		if (!line.isEmpty()) {
			renderer.text(font, line.toString(), x, lineY, color);
			lineY += font.lineHeight + 1;
		}
		return lineY;
	}

	private int fade(int color, double amount) {
		return ColorUtil.scaleAlpha(color, Math.max(0.0, Math.min(1.0, amount)));
	}

	// -- state ---------------------------------------------------------------

	private static int categoryIndex(Category category) {
		Category[] values = Category.values();
		for (int i = 0; i < values.length; i++) {
			if (values[i] == category) {
				return i;
			}
		}
		return 0;
	}

	/**
	 * The modules the list should show: the selected category, or every fuzzy match across all
	 * categories when a query is typed. Searching deliberately ignores the rail rather than
	 * filtering within it, since not knowing which category something is in is most of why you
	 * would search.
	 */
	private List<Module> visibleModules() {
		String query = searchBar.query();
		if (query.isEmpty()) {
			return registry.byCategory(selectedCategory);
		}
		List<Module> matches = new ArrayList<>();
		for (Module module : registry.all()) {
			if (FuzzyMatch.match(query, module.name()) != null) {
				matches.add(module);
			}
		}
		return matches;
	}

	private void select(Module module) {
		if (module == selectedModule) {
			return;
		}
		selectedModule = module;
		detailScroll = 0;
		detailFade.snapTo(0);
		detailFade.set(1);
		if (module == null) {
			detailWidgets = List.of();
			return;
		}
		List<SettingWidget> widgets = new ArrayList<>();
		for (Setting<?> setting : module.settings()) {
			widgets.add(WidgetFactory.create(setting, theme, this));
		}
		detailWidgets = widgets;
	}

	// -- input ---------------------------------------------------------------

	@Override
	public boolean isCapturingTextInput() {
		return searchBar.isFocused()
				|| (activePopup != null && activePopup.isCapturingInput())
				|| detailWidgets.stream().anyMatch(SettingWidget::isCapturingInput);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		if (activePopup != null) {
			if (activePopup.mouseClicked(event, doubleClick)) {
				return true;
			}
			if (!activePopup.contains(mx, my)) {
				activePopup = null;
			}
			return true;
		}
		int rise = (int) Math.round((1.0 - entry.get()) * ENTRY_RISE);
		int wy = windowY + rise;

		if (searchBar.contains(mx, my)) {
			searchBar.mouseClicked(event);
			return true;
		}
		searchBar.mouseClicked(event);

		int buttonY = wy + (HEADER_HEIGHT - 20) / 2;
		if (my >= buttonY && my < buttonY + 20) {
			if (mx >= hudButtonX && mx < hudButtonX + hudButtonWidth) {
				Minecraft.getInstance().gui.setScreen(new HudEditorScreen(hudManager));
				return true;
			}
			if (mx >= configsButtonX && mx < configsButtonX + configsButtonWidth) {
				Minecraft.getInstance().gui.setScreen(new ConfigsScreen(configManager, registry, theme, this));
				return true;
			}
			if (mx >= settingsButtonX && mx < settingsButtonX + ICON_BUTTON_SIZE) {
				Minecraft.getInstance().gui.setScreen(new SettingsScreen(settings, registry, theme, this));
				return true;
			}
			if (mx >= accentSwatchX && mx < accentSwatchX + accentSwatchSize) {
				activePopup = new ColorPickerPopup(theme.accent(), color -> {
					theme.setAccent(color);
					guiConfig.setAccent(color);
				}, theme, accentSwatchX - ColorPickerPopup.WIDTH, buttonY + 26);
				return true;
			}
		}

		// Rail
		int railX = windowX + PADDING;
		int railTop = wy + HEADER_HEIGHT + PADDING - 6;
		Category[] categories = Category.values();
		if (mx >= railX - 6 && mx < railX + RAIL_WIDTH - PADDING - 6) {
			int index = (int) ((my - railTop) / RAIL_ROW_HEIGHT);
			if (my >= railTop && index >= 0 && index < categories.length) {
				selectedCategory = categories[index];
				guiConfig.setLastCategory(selectedCategory);
				listScroll = 0;
				List<Module> modules = visibleModules();
				select(modules.isEmpty() ? null : modules.getFirst());
				return true;
			}
		}

		// Detail settings first: they overlap nothing else, and a click on a widget must not also
		// count as a click on the list row behind the pane's left edge.
		for (SettingWidget widget : detailWidgets) {
			if (widget.setting().isVisible() && widget.mouseClicked(event, doubleClick)) {
				return true;
			}
		}

		// Module list
		int listX = windowX + RAIL_WIDTH + PADDING;
		int listTop = wy + HEADER_HEIGHT + PADDING - 6;
		int listWidth = windowWidth - RAIL_WIDTH - DETAIL_WIDTH - PADDING * 2;
		int listBottom = wy + windowHeight - PADDING;
		if (mx >= listX - 6 && mx < listX + listWidth && my >= listTop && my < listBottom) {
			int index = (int) ((my - listTop + listScroll) / ROW_HEIGHT);
			List<Module> modules = visibleModules();
			if (index >= 0 && index < modules.size()) {
				Module module = modules.get(index);
				if (mx >= listX + listWidth - 30 && mx < listX + listWidth - 30 + 24) {
					module.toggle();
				} else {
					select(module);
				}
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (activePopup != null && activePopup.mouseReleased(event)) {
			return true;
		}
		for (SettingWidget widget : detailWidgets) {
			if (widget.mouseReleased(event)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (activePopup != null && activePopup.mouseDragged(event, dragX, dragY)) {
			return true;
		}
		for (SettingWidget widget : detailWidgets) {
			if (widget.mouseDragged(event, dragX, dragY)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		for (SettingWidget widget : detailWidgets) {
			if (widget.setting().isVisible() && widget.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
				return true;
			}
		}
		int detailX = windowX + windowWidth - DETAIL_WIDTH;
		if (mouseX >= detailX) {
			detailScroll = Math.max(0, detailScroll - scrollY * 18);
			return true;
		}
		listScroll = Math.max(0, listScroll - scrollY * 18);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (activePopup != null && activePopup.keyPressed(event)) {
			return true;
		}
		for (SettingWidget widget : detailWidgets) {
			if (widget.setting().isVisible() && widget.keyPressed(event)) {
				return true;
			}
		}
		if (searchBar.keyPressed(event)) {
			listScroll = 0;
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
		for (SettingWidget widget : detailWidgets) {
			if (widget.setting().isVisible() && widget.charTyped(event)) {
				return true;
			}
		}
		if (searchBar.charTyped(event)) {
			listScroll = 0;
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public void openColorPicker(ColorSetting setting, int swatchX, int swatchY, int swatchWidth, int swatchHeight) {
		int outerW = ColorPickerPopup.outerWidth();
		int outerH = ColorPickerPopup.outerHeight();
		// Left of the swatch by preference here, unlike the panel GUI: the settings pane is against
		// the right edge of the window, so the room is always on the other side.
		int paintedX = swatchX - PICKER_GAP - outerW;
		if (paintedX < PICKER_GAP) {
			paintedX = swatchX + swatchWidth + PICKER_GAP;
		}
		int paintedY = Math.max(PICKER_GAP, Math.min(swatchY + swatchHeight / 2 - outerH / 2, height - outerH - PICKER_GAP));
		int padding = ColorPickerPopup.padding();
		this.activePopup = new ColorPickerPopup(setting.get(), setting::set, theme, paintedX + padding, paintedY + padding);
	}
}

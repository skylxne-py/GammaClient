package dev.gamma.gui.clickgui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import dev.gamma.config.setting.Setting;
import dev.gamma.gui.clickgui.Theme;
import dev.gamma.render.Renderer2D;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shared chip-list editor for the three registry-backed list settings (blocks, entity types,
 * items): removable chips that wrap across as many rows as the current values need (rather than
 * cutting off in a single row you had to scroll sideways through), plus a text field that
 * searches the registry as you type and shows matching suggestions to click or Enter-select,
 * instead of requiring the exact id up front. Subclasses only supply the registry-specific bits
 * (current values, display name, name search/parsing) — see the design notes for why
 * this is one shared widget instead of three near-identical copies.
 */
public abstract class AbstractListSettingWidget<T> extends SettingWidget {

	private static final int CHIP_HEIGHT = 18;
	private static final int CHIP_GAP = 6;
	private static final int CHIP_ROW_GAP = 4;
	private static final int INPUT_HEIGHT = 18;
	private static final int ROW_GAP = 4;
	private static final int SUGGESTION_HEIGHT = 16;
	/**
	 * How many matches {@link #search} should collect. Also the cap on how far the dropdown can be
	 * scrolled -- a query like "redstone" matches far more than fits on screen, and stopping at the
	 * handful that happened to be visible meant most of them could never be selected at all.
	 * Bounded rather than unlimited because this reruns every frame the dropdown is open.
	 */
	protected static final int MAX_SUGGESTIONS = 64;
	/** Rows visible at once. Past about this many the dropdown is taller than the settings area it opens inside; the rest scroll. */
	private static final int VISIBLE_SUGGESTIONS = 4;
	// height() has no Font to measure with (see assumedLineHeight()), so chip-wrapping there
	// uses this rough per-character estimate rather than exact widths -- render() below still
	// wraps using the real font, this only needs to be close enough that the space height()
	// reserves roughly matches what render() actually draws into.
	private static final int ESTIMATED_CHAR_WIDTH = 6;

	private final List<Chip> lastChips = new ArrayList<>();
	/** The rows actually drawn this frame, so a click maps to what the user saw rather than to the unscrolled list. */
	private final List<T> lastSuggestions = new ArrayList<>();
	private StringBuilder input = new StringBuilder();
	private boolean focused;
	private String error;
	private int suggestionScroll;
	/** Where {@link #render} actually put the input box last frame; see {@link #inputY()}. */
	private int lastInputY = -1;
	/** Last query the scroll offset was valid for -- a new query starts at the top. */
	private String lastQuery = "";

	protected AbstractListSettingWidget(Setting<?> setting, Theme theme) {
		super(setting, theme);
	}

	protected abstract List<T> values();

	protected abstract void setValues(List<T> values);

	protected abstract String displayName(T value);

	/** Case-insensitive substring search against the registry, for the add-by-search dropdown. Order is up to the implementation; only the first {@link #MAX_SUGGESTIONS} are shown. */
	protected abstract List<T> search(String query);

	/** Exact-id fallback for when search comes up empty (e.g. a modded id fuzzy substring matching won't find). */
	protected abstract Optional<T> parse(String query);

	@Override
	public int height() {
		int chipRows = Math.max(1, estimateChipRows());
		return assumedLineHeight() + ROW_GAP + chipRows * CHIP_HEIGHT + (chipRows - 1) * CHIP_ROW_GAP + ROW_GAP + INPUT_HEIGHT;
	}

	/** {@link #height()} can't take a {@code Font}, so this mirrors vanilla's default 9px line height used elsewhere for layout math. */
	private static int assumedLineHeight() {
		return 9;
	}

	private int estimateChipRows() {
		List<T> vals = values();
		if (vals.isEmpty()) {
			return 1;
		}
		int rows = 1;
		int cursor = 0;
		for (T value : vals) {
			int chipWidth = displayName(value).length() * ESTIMATED_CHAR_WIDTH + 20;
			if (cursor > 0 && cursor + chipWidth > width) {
				rows++;
				cursor = 0;
			}
			cursor += chipWidth + CHIP_GAP;
		}
		return rows;
	}

	@Override
	public boolean isCapturingInput() {
		return focused;
	}

	@Override
	public void render(Renderer2D renderer, Font font, int mouseX, int mouseY, float partialTick) {
		int labelY = y;
		renderer.text(font, setting.name() + " (" + values().size() + ")", x, labelY, theme.textPrimary());

		int chipAreaY = labelY + font.lineHeight + ROW_GAP;
		lastChips.clear();
		int cursor = x;
		int rowY = chipAreaY;
		for (T value : values()) {
			String name = displayName(value);
			int chipWidth = font.width(name) + 20;
			if (cursor > x && cursor + chipWidth > x + width) {
				cursor = x;
				rowY += CHIP_HEIGHT + CHIP_ROW_GAP;
			}
			Chip chip = new Chip(value, cursor, rowY, chipWidth);
			lastChips.add(chip);
			boolean hoveringRemove = mouseX >= chip.removeX() && mouseX < cursor + chipWidth
					&& mouseY >= rowY && mouseY < rowY + CHIP_HEIGHT;
			renderer.roundedRect(cursor, rowY, chipWidth, CHIP_HEIGHT, CHIP_HEIGHT / 2, theme.settingsBackground());
			renderer.text(font, name, cursor + 8, rowY + (CHIP_HEIGHT - font.lineHeight) / 2, theme.textPrimary());
			renderer.text(font, "x", chip.removeX() + 4, rowY + (CHIP_HEIGHT - font.lineHeight) / 2,
					hoveringRemove ? theme.accentHover() : theme.textSecondary());
			cursor += chipWidth + CHIP_GAP;
		}
		int chipAreaBottom = values().isEmpty() ? chipAreaY : rowY + CHIP_HEIGHT;

		int inputY = chipAreaBottom + ROW_GAP;
		lastInputY = inputY;
		renderer.roundedRect(x, inputY, width, INPUT_HEIGHT, 5, theme.settingsBackground());
		if (focused) {
			renderer.roundedRectOutline(x, inputY, width, INPUT_HEIGHT, 5, 1.5f, error != null ? 0xFFE05050 : theme.accent());
		}
		String text = input.length() == 0 && !focused ? "Search to add..." : input.toString();
		int textColor = input.length() == 0 && !focused ? theme.textSecondary() : theme.textPrimary();
		renderer.text(font, text, x + 6, inputY + (INPUT_HEIGHT - font.lineHeight) / 2, textColor);

	}

	/**
	 * The search dropdown, drawn in the overlay pass so it lands on top of the settings below it
	 * rather than under them. Opaque, scrollable, and capped at {@link #VISIBLE_SUGGESTIONS} rows.
	 */
	@Override
	public void renderOverlay(Renderer2D renderer, Font font, int mouseX, int mouseY, float partialTick) {
		lastSuggestions.clear();
		if (!focused || input.isEmpty()) {
			return;
		}
		String query = input.toString().trim();
		if (!query.equals(lastQuery)) {
			lastQuery = query;
			suggestionScroll = 0;
		}
		List<T> matches = search(query);
		if (matches.isEmpty()) {
			return;
		}
		int shown = Math.min(matches.size(), VISIBLE_SUGGESTIONS);
		suggestionScroll = Math.max(0, Math.min(suggestionScroll, matches.size() - shown));

		int suggestionY = inputY() + INPUT_HEIGHT + 2;
		renderer.fill(x, suggestionY, x + width, suggestionY + shown * SUGGESTION_HEIGHT, theme.dropdownBackground());
		for (int i = 0; i < shown; i++) {
			T candidate = matches.get(suggestionScroll + i);
			lastSuggestions.add(candidate);
			int rowTop = suggestionY + i * SUGGESTION_HEIGHT;
			boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= rowTop && mouseY < rowTop + SUGGESTION_HEIGHT;
			if (hovered) {
				renderer.fill(x, rowTop, x + width, rowTop + SUGGESTION_HEIGHT, theme.rowHoverBackground());
			}
			renderer.text(font, displayName(candidate), x + 6, rowTop + (SUGGESTION_HEIGHT - font.lineHeight) / 2, theme.textPrimary());
		}

		// "3 of 47" rather than a scrollbar: the dropdown is four rows tall, so a thumb would be a
		// couple of pixels and tell you less than the number does.
		if (matches.size() > shown) {
			String position = (suggestionScroll + 1) + "-" + (suggestionScroll + shown) + " of " + matches.size()
					+ (matches.size() >= MAX_SUGGESTIONS ? "+" : "");
			renderer.text(font, position, x + width - font.width(position) - 6,
					suggestionY + shown * SUGGESTION_HEIGHT - SUGGESTION_HEIGHT + (SUGGESTION_HEIGHT - font.lineHeight) / 2,
					theme.textSecondary());
		}
	}

	private void add(T value) {
		List<T> updated = new ArrayList<>(values());
		if (!updated.contains(value)) {
			updated.add(value);
			setValues(updated);
		}
		input = new StringBuilder();
		error = null;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) {
			return false;
		}
		double mx = event.x();
		double my = event.y();
		int suggestionIndex = suggestionIndexAt(mx, my);
		if (suggestionIndex >= 0) {
			add(lastSuggestions.get(suggestionIndex));
			return true;
		}
		for (Chip chip : lastChips) {
			if (mx >= chip.removeX() && mx < chip.removeX() + 14 && my >= chip.y() && my < chip.y() + CHIP_HEIGHT) {
				@SuppressWarnings("unchecked")
				T target = (T) chip.value();
				List<T> updated = new ArrayList<>(values());
				updated.remove(target);
				setValues(updated);
				return true;
			}
		}
		int inputY = inputY();
		boolean onInput = mx >= x && mx < x + width && my >= inputY && my < inputY + INPUT_HEIGHT;
		if (onInput) {
			focused = true;
			return true;
		}
		if (focused) {
			focused = false;
		}
		return isHovered(mx, my);
	}

	/**
	 * Top of the input box. Prefers the value {@link #render} actually used, because that one wraps
	 * chips with real font widths while the fallback below can only estimate — and the dropdown has
	 * to line up with the box a user can see, not with the estimate. The estimate is still the
	 * answer before the first frame is drawn.
	 */
	private int inputY() {
		if (lastInputY >= 0) {
			return lastInputY;
		}
		int chipRows = Math.max(1, estimateChipRows());
		int chipAreaY = y + assumedLineHeight() + ROW_GAP;
		int chipAreaBottom = values().isEmpty() ? chipAreaY : chipAreaY + chipRows * CHIP_HEIGHT + (chipRows - 1) * CHIP_ROW_GAP;
		return chipAreaBottom + ROW_GAP;
	}

	private int suggestionIndexAt(double mouseX, double mouseY) {
		if (lastSuggestions.isEmpty()) {
			return -1;
		}
		int suggestionY = inputY() + INPUT_HEIGHT + 2;
		if (mouseX < x || mouseX >= x + width || mouseY < suggestionY) {
			return -1;
		}
		int index = (int) ((mouseY - suggestionY) / SUGGESTION_HEIGHT);
		return index >= 0 && index < lastSuggestions.size() ? index : -1;
	}

	/** Scrolls the dropdown when the cursor is over it, so the wheel doesn't scroll the settings area out from under an open search instead. */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (lastSuggestions.isEmpty() || !overDropdown(mouseX, mouseY)) {
			return false;
		}
		suggestionScroll = Math.max(0, suggestionScroll - (int) Math.signum(scrollY));
		return true;
	}

	private boolean overDropdown(double mouseX, double mouseY) {
		int suggestionY = inputY() + INPUT_HEIGHT + 2;
		return mouseX >= x && mouseX < x + width
				&& mouseY >= suggestionY && mouseY < suggestionY + lastSuggestions.size() * SUGGESTION_HEIGHT;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (!focused) {
			return false;
		}
		input.append(event.codepointAsString());
		error = null;
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!focused) {
			return false;
		}
		if (event.key() == InputConstants.KEY_BACKSPACE && input.length() > 0) {
			input.deleteCharAt(input.length() - 1);
			return true;
		}
		if (event.key() == InputConstants.KEY_RETURN) {
			String query = input.toString().trim();
			List<T> matches = search(query);
			if (!matches.isEmpty()) {
				add(matches.get(0));
				return true;
			}
			Optional<T> parsed = parse(query);
			if (parsed.isPresent()) {
				add(parsed.get());
			} else if (!query.isEmpty()) {
				error = "Unknown: " + query;
			}
			return true;
		}
		if (event.key() == InputConstants.KEY_ESCAPE) {
			focused = false;
			return true;
		}
		return false;
	}

	private record Chip(Object value, int x, int y, int totalWidth) {
		int removeX() {
			return x + totalWidth - 16;
		}
	}
}

package dev.gamma.gui.clickgui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import dev.gamma.config.setting.StringSetting;
import dev.gamma.gui.clickgui.Theme;
import dev.gamma.render.Renderer2D;
import dev.gamma.util.ColorUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * A minimal inline text field — click to focus, type, arrow keys move the cursor, Enter/Escape
 * unfocuses, and the usual clipboard shortcuts work.
 *
 * <h2>Why every edit commits immediately</h2>
 *
 * <p>This used to hold the text in a buffer and write it to the {@link StringSetting} only on
 * Enter, Escape, or a click elsewhere. Every other way of leaving the field threw the text away —
 * including the most natural one, pressing the ClickGUI's own bind to close it, which tears the
 * screen down without any of those three happening. Pasting a Spotify client id and closing the
 * GUI lost it every time. There is no validation or cost to writing a string setting, so the buffer
 * is now only a cursor-editing convenience and the setting is updated on every keystroke; nothing
 * can be lost by the way the field is left.
 *
 * <h2>Select-all rather than a full selection model</h2>
 *
 * <p>Ctrl+A sets a flag that makes the next edit replace the whole field, instead of tracking an
 * arbitrary selection range. That covers what the field is actually for — clear it and paste an id
 * — without the drag-select, shift-arrow and partial-range handling a real selection needs, all of
 * which is surface area for bugs in a control this small.
 */
public final class StringSettingWidget extends SettingWidget {

	private static final int FIELD_WIDTH = 100;

	private final StringSetting stringSetting;
	private StringBuilder buffer;
	private int cursor;
	private boolean focused;
	/** Whether the whole field is selected, so the next edit replaces it — see the class doc. */
	private boolean allSelected;

	public StringSettingWidget(StringSetting setting, Theme theme) {
		super(setting, theme);
		this.stringSetting = setting;
		this.buffer = new StringBuilder(setting.get());
		this.cursor = buffer.length();
	}

	@Override
	public boolean isCapturingInput() {
		return focused;
	}

	@Override
	public void render(Renderer2D renderer, Font font, int mouseX, int mouseY, float partialTick) {
		syncFromSetting();
		int labelY = y + (height() - font.lineHeight) / 2;
		renderer.text(font, setting.name(), x, labelY, theme.textPrimary());

		int fieldX = x + width - FIELD_WIDTH;
		renderer.roundedRect(fieldX, y + 1, FIELD_WIDTH, height() - 2, 5, theme.settingsBackground());
		if (focused) {
			renderer.roundedRectOutline(fieldX, y + 1, FIELD_WIDTH, height() - 2, 5, 1.5f, theme.accent());
		}

		String text = buffer.toString();
		int textX = fieldX + 6;
		int maxWidth = FIELD_WIDTH - 12;
		if (font.width(text) > maxWidth) {
			// Keep the end visible rather than the start: what you just typed or pasted matters more
			// than the beginning of an id you can't read at this width anyway.
			text = trimToEnd(font, text, maxWidth);
		}
		if (allSelected && !text.isEmpty()) {
			renderer.fill(textX - 1, y + 3, textX + font.width(text) + 1, y + height() - 3, ColorUtil.scaleAlpha(theme.accent(), 0.35));
		}
		renderer.text(font, text, textX, labelY, theme.textPrimary());

		if (focused && !allSelected && (System.currentTimeMillis() / 500) % 2 == 0) {
			int cursorX = textX + font.width(text.substring(0, Math.min(cursor, text.length())));
			renderer.verticalLine(cursorX, y + 3, y + height() - 3, theme.accent());
		}
	}

	/** Longest suffix of {@code text} that fits, so the caret end of a long value stays on screen. */
	private static String trimToEnd(Font font, String text, int maxWidth) {
		int start = 0;
		while (start < text.length() && font.width(text.substring(start)) > maxWidth) {
			start++;
		}
		return text.substring(start);
	}

	/** Picks up changes made to the setting elsewhere (config load, a command) while not being edited. */
	private void syncFromSetting() {
		if (focused || buffer.toString().equals(stringSetting.get())) {
			return;
		}
		buffer = new StringBuilder(stringSetting.get());
		cursor = buffer.length();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		boolean hit = isHovered(event.x(), event.y());
		if (hit && event.button() == 0) {
			focused = true;
			allSelected = doubleClick;
			cursor = buffer.length();
			return true;
		}
		if (focused && !hit) {
			unfocus();
		}
		return false;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (!focused) {
			return false;
		}
		insert(event.codepointAsString());
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!focused) {
			return false;
		}
		if (event.isPaste()) {
			insert(Minecraft.getInstance().keyboardHandler.getClipboard());
			return true;
		}
		if (event.isSelectAll()) {
			allSelected = true;
			return true;
		}
		if (event.isCopy() || event.isCut()) {
			Minecraft.getInstance().keyboardHandler.setClipboard(buffer.toString());
			if (event.isCut()) {
				replaceAll("");
			}
			return true;
		}
		switch (event.key()) {
			case InputConstants.KEY_BACKSPACE -> {
				if (allSelected) {
					replaceAll("");
				} else if (cursor > 0) {
					buffer.deleteCharAt(cursor - 1);
					cursor--;
					commit();
				}
			}
			case InputConstants.KEY_DELETE -> {
				if (allSelected) {
					replaceAll("");
				} else if (cursor < buffer.length()) {
					buffer.deleteCharAt(cursor);
					commit();
				}
			}
			case InputConstants.KEY_LEFT -> {
				allSelected = false;
				cursor = Math.max(0, cursor - 1);
			}
			case InputConstants.KEY_RIGHT -> {
				allSelected = false;
				cursor = Math.min(buffer.length(), cursor + 1);
			}
			case InputConstants.KEY_HOME -> {
				allSelected = false;
				cursor = 0;
			}
			case InputConstants.KEY_END -> {
				allSelected = false;
				cursor = buffer.length();
			}
			case InputConstants.KEY_RETURN, InputConstants.KEY_ESCAPE -> unfocus();
			default -> {
				return false;
			}
		}
		return true;
	}

	private void insert(String text) {
		if (text == null || text.isEmpty()) {
			return;
		}
		// Clipboard content can carry the newline from however it was copied; a single-line field
		// would otherwise store a string with a line break in it that never renders.
		String cleaned = text.replaceAll("[\\r\\n\\t]", "");
		if (allSelected) {
			replaceAll(cleaned);
			return;
		}
		buffer.insert(cursor, cleaned);
		cursor += cleaned.length();
		commit();
	}

	private void replaceAll(String text) {
		buffer = new StringBuilder(text);
		cursor = buffer.length();
		allSelected = false;
		commit();
	}

	private void commit() {
		stringSetting.set(buffer.toString());
	}

	private void unfocus() {
		focused = false;
		allSelected = false;
		commit();
	}
}

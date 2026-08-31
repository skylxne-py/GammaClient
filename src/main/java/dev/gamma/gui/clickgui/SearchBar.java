package dev.gamma.gui.clickgui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.gamma.render.Renderer2D;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/** The fuzzy search bar filtering module rows across every panel at once. */
public final class SearchBar {

	public static final int WIDTH = 220;
	public static final int HEIGHT = 24;

	private final Theme theme;
	private int x;
	private int y;
	private StringBuilder buffer = new StringBuilder();
	private boolean focused;

	public SearchBar(Theme theme) {
		this.theme = theme;
	}

	public void setPosition(int x, int y) {
		this.x = x;
		this.y = y;
	}

	public int x() {
		return x;
	}

	public String query() {
		return buffer.toString();
	}

	public boolean isFocused() {
		return focused;
	}

	public void render(Renderer2D renderer, Font font) {
		renderer.roundedRect(x, y, WIDTH, HEIGHT, HEIGHT / 2, theme.panelBackground());
		if (focused) {
			renderer.roundedRectOutline(x, y, WIDTH, HEIGHT, HEIGHT / 2, 1.5f, theme.accent());
		}
		String text = buffer.length() == 0 ? "Search modules..." : buffer.toString();
		int color = buffer.length() == 0 ? theme.textSecondary() : theme.textPrimary();
		renderer.text(font, text, x + 12, y + (HEIGHT - font.lineHeight) / 2, color);
	}

	public boolean contains(double mouseX, double mouseY) {
		return mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + HEIGHT;
	}

	public boolean mouseClicked(MouseButtonEvent event) {
		focused = contains(event.x(), event.y());
		return focused;
	}

	public boolean charTyped(CharacterEvent event) {
		if (!focused) {
			return false;
		}
		buffer.append(event.codepointAsString());
		return true;
	}

	public boolean keyPressed(KeyEvent event) {
		if (!focused) {
			return false;
		}
		if (event.key() == InputConstants.KEY_BACKSPACE && buffer.length() > 0) {
			buffer.deleteCharAt(buffer.length() - 1);
			return true;
		}
		if (event.key() == InputConstants.KEY_ESCAPE) {
			if (buffer.length() > 0) {
				buffer = new StringBuilder();
			} else {
				focused = false;
			}
			return true;
		}
		return focused;
	}
}

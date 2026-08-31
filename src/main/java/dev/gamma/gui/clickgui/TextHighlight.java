package dev.gamma.gui.clickgui;

import dev.gamma.render.Renderer2D;
import net.minecraft.client.gui.Font;

/** Draws text with a subset of character indices (e.g. fuzzy search matches) picked out in a highlight color. */
public final class TextHighlight {

	private TextHighlight() {
	}

	public static void render(Renderer2D renderer, Font font, String text, int[] matchedIndices, int x, int y, int baseColor, int highlightColor) {
		if (matchedIndices == null || matchedIndices.length == 0) {
			renderer.text(font, text, x, y, baseColor);
			return;
		}
		boolean[] highlighted = new boolean[text.length()];
		for (int index : matchedIndices) {
			if (index >= 0 && index < highlighted.length) {
				highlighted[index] = true;
			}
		}
		int cursor = x;
		int i = 0;
		while (i < text.length()) {
			boolean current = highlighted[i];
			int start = i;
			while (i < text.length() && highlighted[i] == current) {
				i++;
			}
			String segment = text.substring(start, i);
			renderer.text(font, segment, cursor, y, current ? highlightColor : baseColor);
			cursor += font.width(segment);
		}
	}
}

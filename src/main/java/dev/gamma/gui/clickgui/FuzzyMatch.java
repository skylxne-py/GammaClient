package dev.gamma.gui.clickgui;

import java.util.Locale;

/** Subsequence fuzzy matching for the ClickGUI search bar: every query character must appear, in order, somewhere in the text. */
public final class FuzzyMatch {

	private FuzzyMatch() {
	}

	/** @return matched character indices into {@code text}, or {@code null} if the query doesn't match at all. */
	public static int[] match(String query, String text) {
		if (query.isEmpty()) {
			return new int[0];
		}
		String q = query.toLowerCase(Locale.ROOT);
		String t = text.toLowerCase(Locale.ROOT);
		int[] indices = new int[q.length()];
		int searchFrom = 0;
		for (int i = 0; i < q.length(); i++) {
			int found = t.indexOf(q.charAt(i), searchFrom);
			if (found < 0) {
				return null;
			}
			indices[i] = found;
			searchFrom = found + 1;
		}
		return indices;
	}
}

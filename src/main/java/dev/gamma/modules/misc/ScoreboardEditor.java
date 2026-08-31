package dev.gamma.modules.misc;

import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.StringSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import net.minecraft.network.chat.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Rewrites the scoreboard sidebar the server is already showing you, for streaming and screenshots.
 *
 * <p>Server sidebars are where a surprising amount leaks: balance, claim name, kill count, rank,
 * often your own username as a row. This edits what is drawn without touching what the client
 * believes — the real objective and scores are untouched, so nothing that reads them behaves
 * differently.
 *
 * <h2>How to drive it</h2>
 *
 * <ul>
 * <li>{@code Title} — replaces the sidebar heading when non-empty.</li>
 * <li>{@code Lines} — replacement rows, top to bottom, separated by {@code |}. An empty entry
 *     leaves that row as it was, so {@code ||Coords: hidden} rewrites only the third row. Extra
 *     entries beyond the number of rows the server is sending are ignored.</li>
 * <li>{@code ScrambleNumbers} — after the above, every remaining digit is replaced with another
 *     digit. This is what covers the common case where the value lives inside the row text
 *     ({@code Balance: $482,193}) rather than in the score column on the right.</li>
 * </ul>
 *
 * <p>Digits are substituted in place rather than the number being regenerated, because vanilla
 * measures the sidebar and right-aligns the score column <em>before</em> drawing. Every digit is
 * the same width in Minecraft's font, so an in-place swap is the only edit that leaves the layout
 * vanilla already computed still correct — a longer or shorter number would visibly shift the box.
 *
 * <p>This is a text editor over one HUD element, not a per-line GUI: the ClickGUI's setting types
 * are single values, so a proper editable table would need a screen of its own. {@code Lines} is
 * the closest thing the existing widgets can express, and it covers the case that matters (blanking
 * or rewriting specific rows) without one.
 */
public final class ScoreboardEditor extends Module {

	public static volatile ScoreboardEditor instance;

	private final StringSetting title = register(new StringSetting("Title", "Replaces the sidebar heading. Leave empty to keep the real one.", ""));
	private final StringSetting lines = register(new StringSetting("Lines", "Replacement rows top to bottom, separated by |. An empty entry keeps that row unchanged.", ""));
	private final BoolSetting scrambleNumbers = register(new BoolSetting("ScrambleNumbers", "Replace every digit left in the sidebar with another digit, keeping the layout identical.", true));

	/**
	 * Re-rolled on every enable rather than persisted: a scramble that survived restarts would show
	 * a viewer the same fake balance twice, which is worse than showing them a real one once.
	 */
	private long seed;

	public ScoreboardEditor() {
		super("ScoreboardEditor", "Rewrites the server's scoreboard sidebar — title, rows, and numbers.", Category.MISC);
		instance = this;
	}

	@Override
	protected void onEnable() {
		seed = ThreadLocalRandom.current().nextLong();
	}

	/** The sidebar heading to draw, given the real one. */
	public Component editTitle(Component real) {
		String override = title.get().trim();
		if (!override.isEmpty()) {
			return Component.literal(override).setStyle(real.getStyle());
		}
		return scramble(real);
	}

	/**
	 * The row to draw at {@code row} (0 = top), given the real one. Applied before
	 * {@code NameProtect}, so a row you have explicitly rewritten stays rewritten.
	 */
	public Component editLine(Component real, int row) {
		String raw = lines.get();
		if (!raw.isEmpty()) {
			String[] replacements = raw.split("\\|", -1);
			if (row >= 0 && row < replacements.length && !replacements[row].isBlank()) {
				return Component.literal(replacements[row]).setStyle(real.getStyle());
			}
		}
		return scramble(real);
	}

	/** The score column value to draw, given the real one. */
	public Component editScore(Component real) {
		return scramble(real);
	}

	private Component scramble(Component component) {
		if (!scrambleNumbers.get()) {
			return component;
		}
		String text = component.getString();
		StringBuilder scrambled = new StringBuilder(text.length());
		boolean changed = false;
		long salt = seed ^ text.hashCode();
		for (int i = 0; i < text.length(); i++) {
			char character = text.charAt(i);
			if (character >= '0' && character <= '9') {
				scrambled.append((char) ('0' + Math.floorMod(mix(salt ^ (i * 0x100000001B3L)), 10)));
				changed = true;
			} else {
				scrambled.append(character);
			}
		}
		return changed ? Component.literal(scrambled.toString()).setStyle(component.getStyle()) : component;
	}

	/** SplitMix64 finalizer — cheap, and spreads the low bits the digit pick takes modulo of. */
	private static long mix(long value) {
		long z = value + 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}
}

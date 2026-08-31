package dev.gamma.core;

import java.util.Locale;

/**
 * ClickGUI sections, in panel order.
 *
 * <p>{@code MOVEMENT} is gone: Freecam was the only module in it, and it is a camera feature
 * rather than a movement one, so it sits under {@code RENDER} now. {@code BASE_HUNTING} groups the
 * modules that exist specifically to find other people's bases, which were otherwise scattered
 * across {@code WORLD}.
 *
 * <p>{@code DONUT_SMP} is the odd one out: it groups by <em>server</em> rather than by what the
 * module does. That is deliberate — these depend on one server's specific mechanics (its custom
 * expiring items, its spawner GUIs, its render distance) and are noise everywhere else, so keeping
 * them in one place someone can ignore wholesale beats scattering them through the functional
 * categories.
 */
public enum Category {
	RENDER,
	ESP,
	WORLD,
	BASE_HUNTING,
	DONUT_SMP,
	MISC;

	/**
	 * Tab title. The old code title-cased {@code name()} inline, which turns the first
	 * multi-word constant into "Base_hunting" — so the mapping lives here instead, next to the
	 * constants it has to stay in step with.
	 */
	public String displayName() {
		return switch (this) {
			case BASE_HUNTING -> "Base Hunting";
			case DONUT_SMP -> "DonutSMP";
			default -> name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
		};
	}
}

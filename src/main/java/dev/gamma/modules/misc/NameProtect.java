package dev.gamma.modules.misc;

import dev.gamma.config.setting.StringSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;

/**
 * Replaces your own name wherever vanilla code asks the local {@code Player} entity for it
 * ({@code getName}/{@code getDisplayName} — nametag, chat prefix, and anything else that reads
 * the entity rather than the raw {@code GameProfile}) — for screenshots/streaming. See
 * {@link dev.gamma.mixin.misc.PlayerNameMixin}.
 */
public final class NameProtect extends Module {

	public static volatile NameProtect instance;

	private final StringSetting replacement = register(new StringSetting("Replacement", "Text to show instead of your name.", "Player"));

	public NameProtect() {
		super("NameProtect", "Replaces your own name in-game for screenshots.", Category.MISC);
		instance = this;
	}

	public String replacement() {
		return replacement.get();
	}
}

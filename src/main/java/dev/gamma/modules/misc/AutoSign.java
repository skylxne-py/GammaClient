package dev.gamma.modules.misc;

import dev.gamma.Gamma;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.StringSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.core.event.Subscription;
import dev.gamma.core.event.events.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignText;

/**
 * Fills the sign editor with four preset lines the moment it opens.
 *
 * <p>Writes the screen's own text buffer rather than simulating keystrokes into the text field. That
 * buffer is what {@code AbstractSignEditScreen.removed()} reads to build the update packet when the
 * screen closes, so filling it is producing exactly what vanilla would have sent had the lines been
 * typed — there is no second "our text" path that could get out of step with what actually goes to
 * the server. The screen's rendered {@code SignText} is updated alongside it so the preview shows
 * what is about to be sent instead of a blank sign.
 *
 * <h2>Line width</h2>
 *
 * <p>Lines are truncated to the sign's own {@code getMaxTextLineWidth()}, the same rule the vanilla
 * editor enforces as you type. Bypassing the buffer means bypassing that check, and the point here
 * is to save typing, not to send something the vanilla client could not have produced. It costs
 * nothing in the normal case: the defaults are far inside the limit.
 *
 * <h2>Why a tick poll and not a screen event</h2>
 *
 * <p>Fabric's screen events are registered globally and cannot be unregistered, so a module that
 * subscribes to one keeps a handler alive after it is switched off and has to re-check its own
 * enabled flag from inside. Polling through {@link Module#listen} instead means the module's normal
 * lifecycle does that for free. The cost is that filling happens a tick after the screen opens,
 * which is not visible — and {@code SpawnerFinder} already reads screen titles the same way.
 */
public final class AutoSign extends Module {

	private static final int LINES = 4;

	private final StringSetting line1 = register(new StringSetting("Line1", "First line.", "RAIDED WITH"));
	private final StringSetting line2 = register(new StringSetting("Line2", "Second line.", "GammaClient"));
	private final StringSetting line3 = register(new StringSetting("Line3", "Third line.", ""));
	private final StringSetting line4 = register(new StringSetting("Line4", "Fourth line.", ""));
	private final BoolSetting confirm = register(new BoolSetting("Confirm", "Close the editor as soon as it is filled, so placing a sign writes it without stopping. Off fills the lines and leaves the screen up for you to check and confirm.", false));

	private Subscription tickSubscription;

	/**
	 * The screen instance already filled. Guarded on the instance rather than a boolean so a sign is
	 * filled once per opening: without it the fill would re-run every tick the editor stays open and
	 * overwrite anything typed into it, which is precisely the case {@code Confirm} off exists for.
	 */
	private Screen lastFilled;

	public AutoSign() {
		super("AutoSign", "Fills a sign with preset lines as soon as its editor opens.", Category.MISC);
	}

	@Override
	protected void onEnable() {
		lastFilled = null;
		tickSubscription = listen(TickEvent.class, this::onTick);
	}

	@Override
	protected void onDisable() {
		Gamma.EVENT_BUS.unsubscribe(tickSubscription);
		lastFilled = null;
	}

	private void onTick(TickEvent event) {
		if (event.phase() != TickEvent.Phase.END) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		Screen screen = client.gui.screen();
		if (!(screen instanceof AbstractSignEditScreen editor)) {
			lastFilled = null;
			return;
		}
		if (screen == lastFilled) {
			return;
		}
		lastFilled = screen;
		fill(client, editor);
	}

	private void fill(Minecraft client, AbstractSignEditScreen editor) {
		String[] lines = {line1.get(), line2.get(), line3.get(), line4.get()};
		int maxWidth = editor.sign.getMaxTextLineWidth();
		SignText text = editor.text;
		for (int i = 0; i < LINES; i++) {
			String line = trimToWidth(client.font, lines[i], maxWidth);
			editor.messages[i] = line;
			text = text.setMessage(i, Component.literal(line));
		}
		editor.text = text;
		editor.sign.setText(text, editor.isFrontText);

		if (confirm.get()) {
			// Closing runs the screen's own removed(), which is what sends the packet -- the same
			// path as clicking Done, not a separate send of our own.
			client.gui.setScreen(null);
		}
	}

	/** The longest prefix of {@code text} that fits, measured the way the vanilla editor measures it. */
	private static String trimToWidth(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return text;
		}
		int end = text.length();
		while (end > 0 && font.width(text.substring(0, end)) > maxWidth) {
			end--;
		}
		return text.substring(0, end);
	}
}

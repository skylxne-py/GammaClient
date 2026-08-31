package dev.gamma.gui.account;

import dev.gamma.account.Account;
import dev.gamma.account.AccountManager;
import dev.gamma.gui.clickgui.Theme;
import dev.gamma.render.Renderer2D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE;

/**
 * The account list: switch, remove, or start adding one.
 *
 * <p>Deliberately not a {@code GammaScreen}. That base class reports itself as in-game UI to avoid
 * a double blur, which is right for panels drawn over a live world — but this one opens from the
 * title screen and the server list, where the vanilla background is the correct thing behind it.
 * It draws no blur of its own, so vanilla's single blur is the only one and there is nothing to
 * collide with.
 */
public final class AccountScreen extends Screen {

	private static final int LIST_X = 40;
	private static final int LIST_TOP = 76;
	private static final int LIST_WIDTH = 300;
	private static final int ROW_HEIGHT = 34;
	private static final int ROW_GAP = 4;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_GAP = 6;
	private static final int BUTTON_PADDING = 12;

	private final Screen parent;

	private String status = "";
	/** Set on the row whose Remove was clicked once, cleared when anything else is. */
	private UUID confirmingRemoval;

	public AccountScreen(Screen parent) {
		super(Component.literal("Gamma Accounts"));
		this.parent = parent;
	}

	private static Theme theme() {
		Theme current = Theme.instance;
		return current != null ? current : new Theme();
	}

	private static AccountManager manager() {
		return AccountManager.instance;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		Renderer2D renderer = new Renderer2D(extractor);
		renderer.setTextShadow(false);
		Theme theme = theme();
		renderer.fill(0, 0, width, height, 0x99000000);

		renderer.text(font, "Accounts", LIST_X, 28, theme.textPrimary());
		renderer.text(font, "Signed in as " + AccountManager.currentName() + ". Esc to go back.",
				LIST_X, 44, theme.textSecondary());

		AccountManager manager = manager();
		if (manager == null) {
			renderer.text(font, "The account manager did not start. Check the log.", LIST_X, LIST_TOP, theme.textSecondary());
			return;
		}

		renderButton(renderer, "+ Add account", LIST_X, LIST_TOP, mouseX, mouseY, !manager.isBusy());

		if (!AccountManager.canSwitch()) {
			renderer.text(font, "Disconnect from the world or server before switching.",
					LIST_X + buttonWidth("+ Add account") + BUTTON_GAP * 2, LIST_TOP + (BUTTON_HEIGHT - font.lineHeight) / 2,
					theme.textSecondary());
		}

		List<Account> accounts = manager.accounts();
		int y = LIST_TOP + BUTTON_HEIGHT + 16;
		if (accounts.isEmpty()) {
			renderer.text(font, "No accounts saved yet. Add one above.", LIST_X, y + 6, theme.textSecondary());
		}
		for (Account account : accounts) {
			renderRow(renderer, theme, account, y, mouseX, mouseY, manager);
			y += ROW_HEIGHT + ROW_GAP;
		}

		String line = manager.isBusy() ? manager.status() : status;
		if (!line.isEmpty()) {
			renderer.text(font, line, LIST_X, height - 20, theme.textSecondary());
		}
	}

	private void renderRow(Renderer2D renderer, Theme theme, Account account, int y, int mouseX, int mouseY, AccountManager manager) {
		boolean current = account.uuid().equals(manager.selected());
		boolean hovered = within(mouseX, mouseY, LIST_X, y, LIST_WIDTH, ROW_HEIGHT);
		renderer.roundedRect(LIST_X, y, LIST_WIDTH, ROW_HEIGHT, 6,
				hovered ? theme.rowHoverBackground() : theme.settingsBackground());
		if (current) {
			// A short accent bar rather than a full plate: it marks the active account without
			// making the row compete with the buttons next to it.
			renderer.roundedRect(LIST_X, y + 6, 3, ROW_HEIGHT - 12, 1, theme.accent());
		}

		renderer.text(font, account.name(), LIST_X + 12, y + 7, current ? theme.accent() : theme.textPrimary());
		renderer.text(font, describeLastUsed(account), LIST_X + 12, y + 19, theme.textSecondary());

		int x = LIST_X + LIST_WIDTH + BUTTON_GAP;
		boolean switchable = !manager.isBusy() && AccountManager.canSwitch() && !current;
		renderButton(renderer, current ? "Active" : "Switch", x, y + (ROW_HEIGHT - BUTTON_HEIGHT) / 2, mouseX, mouseY, switchable);
		x += buttonWidth(current ? "Active" : "Switch") + BUTTON_GAP;

		String removeLabel = account.uuid().equals(confirmingRemoval) ? "Sure?" : "Remove";
		renderButton(renderer, removeLabel, x, y + (ROW_HEIGHT - BUTTON_HEIGHT) / 2, mouseX, mouseY, !manager.isBusy());
	}

	/** Relative time, because an absolute timestamp on a list like this is noise. */
	private static String describeLastUsed(Account account) {
		if (account.lastUsed() <= 0L) {
			return "never used";
		}
		Duration since = Duration.ofMillis(Math.max(0L, System.currentTimeMillis() - account.lastUsed()));
		if (since.toMinutes() < 1) {
			return "used just now";
		}
		if (since.toHours() < 1) {
			return "used " + since.toMinutes() + "m ago";
		}
		if (since.toDays() < 1) {
			return "used " + since.toHours() + "h ago";
		}
		return "used " + since.toDays() + "d ago";
	}

	private void renderButton(Renderer2D renderer, String label, int x, int y, int mouseX, int mouseY, boolean enabled) {
		Theme theme = theme();
		int w = buttonWidth(label);
		boolean hovered = enabled && within(mouseX, mouseY, x, y, w, BUTTON_HEIGHT);
		renderer.roundedRect(x, y, w, BUTTON_HEIGHT, 5, hovered ? theme.rowHoverBackground() : theme.settingsBackground());
		int color = enabled ? theme.textPrimary() : theme.textSecondary();
		renderer.text(font, label, x + BUTTON_PADDING / 2, y + (BUTTON_HEIGHT - font.lineHeight) / 2, color);
	}

	private int buttonWidth(String label) {
		return font.width(label) + BUTTON_PADDING;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) {
			return false;
		}
		AccountManager manager = manager();
		if (manager == null || manager.isBusy()) {
			return false;
		}
		double mx = event.x();
		double my = event.y();

		if (within(mx, my, LIST_X, LIST_TOP, buttonWidth("+ Add account"), BUTTON_HEIGHT)) {
			confirmingRemoval = null;
			minecraft.setScreenAndShow(new AddAccountScreen(this));
			return true;
		}

		int y = LIST_TOP + BUTTON_HEIGHT + 16;
		for (Account account : manager.accounts()) {
			boolean current = account.uuid().equals(manager.selected());
			int buttonY = y + (ROW_HEIGHT - BUTTON_HEIGHT) / 2;
			int x = LIST_X + LIST_WIDTH + BUTTON_GAP;

			String switchLabel = current ? "Active" : "Switch";
			if (within(mx, my, x, buttonY, buttonWidth(switchLabel), BUTTON_HEIGHT)) {
				confirmingRemoval = null;
				if (!current) {
					beginSwitch(manager, account);
				}
				return true;
			}
			x += buttonWidth(switchLabel) + BUTTON_GAP;

			String removeLabel = account.uuid().equals(confirmingRemoval) ? "Sure?" : "Remove";
			if (within(mx, my, x, buttonY, buttonWidth(removeLabel), BUTTON_HEIGHT)) {
				// Two-click confirm rather than a dialog: removing an account is recoverable (add
				// it again) but annoying enough that a stray click should not do it.
				if (account.uuid().equals(confirmingRemoval)) {
					manager.remove(account);
					status = "Removed " + account.name() + ".";
					confirmingRemoval = null;
				} else {
					confirmingRemoval = account.uuid();
				}
				return true;
			}
			y += ROW_HEIGHT + ROW_GAP;
		}

		confirmingRemoval = null;
		return false;
	}

	private void beginSwitch(AccountManager manager, Account account) {
		status = "";
		manager.switchTo(account,
				() -> status = "Signed in as " + account.name() + ".",
				message -> status = message);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == KEY_ESCAPE) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreenAndShow(parent);
	}

	private static boolean within(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}

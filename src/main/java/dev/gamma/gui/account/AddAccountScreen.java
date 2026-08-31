package dev.gamma.gui.account;

import com.mojang.blaze3d.platform.InputConstants;
import dev.gamma.Gamma;
import dev.gamma.account.AccountManager;
import dev.gamma.account.MsaAuth;
import dev.gamma.core.keybind.TextInputCapture;
import dev.gamma.gui.clickgui.Theme;
import dev.gamma.render.Renderer2D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE;

/**
 * Signing in to Microsoft: opens the browser and waits, with a device code fallback for when it
 * cannot.
 *
 * <p>The login itself lives on {@link AccountManager}; this screen holds display state only. That
 * split matters because the wait outlives the screen — it sits on a worker thread for as long as
 * the user takes in their browser, and closing the screen has to cancel it rather than leave a
 * loopback listener open against fields nobody is looking at. {@link #cancelled} is that switch and
 * {@link #onClose()} flips it.
 */
public final class AddAccountScreen extends Screen implements TextInputCapture {

	private static final int CONTENT_X = 40;
	private static final int CONTENT_TOP = 76;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_GAP = 6;
	private static final int BUTTON_PADDING = 12;
	private static final int LINE = 12;
	private static final int CODE_BOX_HEIGHT = 40;
	private static final int CODE_BOX_WIDTH = 300;

	private static final int FIELD_WIDTH = 380;
	private static final int FIELD_HEIGHT = 20;
	private static final int MAX_PASTE_LENGTH = 2048;

	/**
	 * Which sign-in is running. {@link #MANUAL} versus {@link #BROWSER} is decided by
	 * {@link MsaAuth#supportsLoopback()}, not by the user — the built-in client id cannot register
	 * a loopback redirect, so it has to ask for the address back. Device code is only reached by
	 * asking for it.
	 */
	private enum Mode {
		BROWSER, MANUAL, DEVICE_CODE
	}

	private final Screen parent;
	private final AtomicBoolean cancelled = new AtomicBoolean(false);

	private Mode mode = MsaAuth.supportsLoopback() ? Mode.BROWSER : Mode.MANUAL;
	private URI authorizeUrl;
	private MsaAuth.DeviceCode code;
	private String error = "";
	private boolean finished;
	private final StringBuilder pasteBuffer = new StringBuilder();
	private boolean submitting;

	public AddAccountScreen(Screen parent) {
		super(Component.literal("Add Account"));
		this.parent = parent;
	}

	private static Theme theme() {
		Theme current = Theme.instance;
		return current != null ? current : new Theme();
	}

	@Override
	protected void init() {
		// init() runs again on every resize, and a window drag must not restart the sign-in. These
		// guards are what make it idempotent.
		if (authorizeUrl != null || code != null || finished || !error.isEmpty()) {
			return;
		}
		start();
	}

	private void start() {
		AccountManager manager = AccountManager.instance;
		if (manager == null) {
			error = "The account manager did not start. Check the log.";
			return;
		}
		if (mode == Mode.MANUAL) {
			// Nothing to wait on: the URL is built locally and the user drives the rest.
			authorizeUrl = new MsaAuth().manualAuthorizeUrl();
			openBrowser();
		} else if (mode == Mode.BROWSER) {
			manager.beginBrowserAdd(url -> {
				authorizeUrl = url;
				openBrowser();
			}, account -> finish(), message -> error = message, cancelled::get);
		} else {
			manager.beginDeviceCodeAdd(issued -> {
				code = issued;
				copyCode();
				openBrowser();
			}, account -> finish(), message -> error = message, cancelled::get);
		}
	}

	private void finish() {
		finished = true;
		Minecraft.getInstance().setScreenAndShow(parent);
	}

	private void openBrowser() {
		URI target = mode == Mode.DEVICE_CODE
				? (code == null ? null : URI.create(code.verificationUri()))
				: authorizeUrl;
		if (target == null) {
			return;
		}
		try {
			Util.getPlatform().openUri(target);
		} catch (Exception e) {
			// Not fatal: the URL is on screen and the button offers another go.
			Gamma.LOGGER.warn("Could not open a browser for the Microsoft sign-in. Open this URL manually: {}", target, e);
		}
	}

	private void copyCode() {
		if (code != null) {
			minecraft.keyboardHandler.setClipboard(code.userCode());
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		Renderer2D renderer = new Renderer2D(extractor);
		renderer.setTextShadow(false);
		Theme theme = theme();
		renderer.fill(0, 0, width, height, 0x99000000);

		renderer.text(font, "Add account", CONTENT_X, 28, theme.textPrimary());

		if (!error.isEmpty()) {
			renderError(renderer, theme, mouseX, mouseY);
		} else {
			switch (mode) {
				case DEVICE_CODE -> renderDeviceCode(renderer, theme, mouseX, mouseY);
				case MANUAL -> renderManual(renderer, theme, mouseX, mouseY);
				case BROWSER -> renderBrowser(renderer, theme, mouseX, mouseY);
			}
		}
	}

	private void renderManual(Renderer2D renderer, Theme theme, int mouseX, int mouseY) {
		renderer.text(font, "Sign in to Microsoft in the browser window that just opened.",
				CONTENT_X, CONTENT_TOP, theme.textPrimary());
		renderer.text(font, "This page comes straight from Microsoft. Gamma never sees your password.",
				CONTENT_X, CONTENT_TOP + LINE + 2, theme.textSecondary());
		renderer.text(font, "When it finishes you land on a blank page. Copy that page's address and paste it here:",
				CONTENT_X, CONTENT_TOP + LINE * 2 + 8, theme.textSecondary());

		int fieldY = CONTENT_TOP + LINE * 3 + 14;
		renderer.roundedRect(CONTENT_X, fieldY, FIELD_WIDTH, FIELD_HEIGHT, 5, theme.settingsBackground());
		renderer.roundedRectOutline(CONTENT_X, fieldY, FIELD_WIDTH, FIELD_HEIGHT, 5, 1.0f, theme.accentMuted(0.6));

		// Tail rather than head: the code is at the end of the URL, so the end is the part worth
		// seeing to know something landed.
		String shown = pasteBuffer.isEmpty() ? "Ctrl+V" : pasteBuffer.toString();
		while (font.width(shown) > FIELD_WIDTH - 12 && shown.length() > 1) {
			shown = shown.substring(1);
		}
		renderer.text(font, shown, CONTENT_X + 6, fieldY + (FIELD_HEIGHT - font.lineHeight) / 2,
				pasteBuffer.isEmpty() ? theme.textSecondary() : theme.textPrimary());

		int y = fieldY + FIELD_HEIGHT + 10;
		renderButton(renderer, submitting ? "Signing in..." : "Sign in", CONTENT_X, y, mouseX, mouseY);
		renderButton(renderer, "Open browser again", CONTENT_X + buttonWidth("Sign in") + BUTTON_GAP, y, mouseX, mouseY);
		renderButton(renderer, "Cancel",
				CONTENT_X + buttonWidth("Sign in") + buttonWidth("Open browser again") + BUTTON_GAP * 2, y, mouseX, mouseY);

		renderer.text(font, "Only needed once per account. Switching later is a single click.",
				CONTENT_X, y + BUTTON_HEIGHT + 14, theme.textSecondary());
	}

	private void renderBrowser(Renderer2D renderer, Theme theme, int mouseX, int mouseY) {
		if (authorizeUrl == null) {
			renderer.text(font, "Starting sign-in...", CONTENT_X, CONTENT_TOP, theme.textSecondary());
			renderButton(renderer, "Cancel", CONTENT_X, CONTENT_TOP + 20, mouseX, mouseY);
			return;
		}
		renderer.text(font, "Sign in to Microsoft in the browser window that just opened.",
				CONTENT_X, CONTENT_TOP, theme.textPrimary());
		renderer.text(font, "This page comes straight from Microsoft. Gamma never sees your password.",
				CONTENT_X, CONTENT_TOP + LINE + 2, theme.textSecondary());
		renderer.text(font, "Waiting for you to finish...", CONTENT_X, CONTENT_TOP + LINE * 2 + 6, theme.textSecondary());

		int y = CONTENT_TOP + LINE * 3 + 12;
		renderButton(renderer, "Open browser again", CONTENT_X, y, mouseX, mouseY);
		renderButton(renderer, "Copy link", CONTENT_X + buttonWidth("Open browser again") + BUTTON_GAP, y, mouseX, mouseY);
		renderButton(renderer, "Cancel",
				CONTENT_X + buttonWidth("Open browser again") + buttonWidth("Copy link") + BUTTON_GAP * 2, y, mouseX, mouseY);

		renderer.text(font, "No browser on this machine? Use a code instead.",
				CONTENT_X, y + BUTTON_HEIGHT + 16, theme.accent());
	}

	private void renderDeviceCode(Renderer2D renderer, Theme theme, int mouseX, int mouseY) {
		if (code == null) {
			renderer.text(font, "Contacting Microsoft...", CONTENT_X, CONTENT_TOP, theme.textSecondary());
			renderButton(renderer, "Cancel", CONTENT_X, CONTENT_TOP + 20, mouseX, mouseY);
			return;
		}
		renderer.text(font, "Go to " + code.verificationUri() + " and enter this code:",
				CONTENT_X, CONTENT_TOP, theme.textSecondary());

		int boxY = CONTENT_TOP + 18;
		renderer.roundedRect(CONTENT_X, boxY, CODE_BOX_WIDTH, CODE_BOX_HEIGHT, 8, theme.settingsBackground());
		renderer.text(font, code.userCode(), CONTENT_X + (CODE_BOX_WIDTH - font.width(code.userCode())) / 2,
				boxY + (CODE_BOX_HEIGHT - font.lineHeight) / 2, theme.accent());

		int y = boxY + CODE_BOX_HEIGHT + 12;
		renderButton(renderer, "Copy code", CONTENT_X, y, mouseX, mouseY);
		renderButton(renderer, "Open browser", CONTENT_X + buttonWidth("Copy code") + BUTTON_GAP, y, mouseX, mouseY);
		renderButton(renderer, "Cancel",
				CONTENT_X + buttonWidth("Copy code") + buttonWidth("Open browser") + BUTTON_GAP * 2, y, mouseX, mouseY);

		renderer.text(font, "The code is on your clipboard. Waiting for approval...",
				CONTENT_X, y + BUTTON_HEIGHT + 14, theme.textSecondary());
	}

	private void renderError(Renderer2D renderer, Theme theme, int mouseX, int mouseY) {
		renderer.text(font, error, CONTENT_X, CONTENT_TOP, theme.textPrimary());
		renderButton(renderer, "Try again", CONTENT_X, CONTENT_TOP + 20, mouseX, mouseY);
		renderButton(renderer, "Back", CONTENT_X + buttonWidth("Try again") + BUTTON_GAP, CONTENT_TOP + 20, mouseX, mouseY);
		if (mode == Mode.BROWSER) {
			renderer.text(font, "No browser on this machine? Use a code instead.",
					CONTENT_X, CONTENT_TOP + 20 + BUTTON_HEIGHT + 16, theme.accent());
		}
	}

	private void renderButton(Renderer2D renderer, String label, int x, int y, int mouseX, int mouseY) {
		Theme theme = theme();
		int w = buttonWidth(label);
		boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + BUTTON_HEIGHT;
		renderer.roundedRect(x, y, w, BUTTON_HEIGHT, 5, hovered ? theme.rowHoverBackground() : theme.settingsBackground());
		renderer.text(font, label, x + BUTTON_PADDING / 2, y + (BUTTON_HEIGHT - font.lineHeight) / 2, theme.textPrimary());
	}

	private int buttonWidth(String label) {
		return font.width(label) + BUTTON_PADDING;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) {
			return false;
		}
		double mx = event.x();
		double my = event.y();

		if (!error.isEmpty()) {
			return clickedError(mx, my);
		}
		return switch (mode) {
			case DEVICE_CODE -> clickedDeviceCode(mx, my);
			case MANUAL -> clickedManual(mx, my);
			case BROWSER -> clickedBrowser(mx, my);
		};
	}

	private boolean clickedManual(double mx, double my) {
		int y = CONTENT_TOP + LINE * 3 + 14 + FIELD_HEIGHT + 10;
		if (within(mx, my, CONTENT_X, y, buttonWidth("Sign in"), BUTTON_HEIGHT)) {
			submit();
			return true;
		}
		int openX = CONTENT_X + buttonWidth("Sign in") + BUTTON_GAP;
		if (within(mx, my, openX, y, buttonWidth("Open browser again"), BUTTON_HEIGHT)) {
			openBrowser();
			return true;
		}
		int cancelX = openX + buttonWidth("Open browser again") + BUTTON_GAP;
		if (within(mx, my, cancelX, y, buttonWidth("Cancel"), BUTTON_HEIGHT)) {
			onClose();
			return true;
		}
		return false;
	}

	private void submit() {
		AccountManager manager = AccountManager.instance;
		if (manager == null || submitting || pasteBuffer.isEmpty()) {
			return;
		}
		submitting = true;
		manager.completeManualAdd(pasteBuffer.toString(), account -> finish(), message -> {
			error = message;
			submitting = false;
		});
	}

	private boolean clickedError(double mx, double my) {
		if (within(mx, my, CONTENT_X, CONTENT_TOP + 20, buttonWidth("Try again"), BUTTON_HEIGHT)) {
			retry();
			return true;
		}
		int backX = CONTENT_X + buttonWidth("Try again") + BUTTON_GAP;
		if (within(mx, my, backX, CONTENT_TOP + 20, buttonWidth("Back"), BUTTON_HEIGHT)) {
			onClose();
			return true;
		}
		if (mode == Mode.BROWSER && clickedFallbackLink(mx, my, CONTENT_TOP + 20 + BUTTON_HEIGHT + 16)) {
			return true;
		}
		return false;
	}

	private boolean clickedBrowser(double mx, double my) {
		if (authorizeUrl == null) {
			if (within(mx, my, CONTENT_X, CONTENT_TOP + 20, buttonWidth("Cancel"), BUTTON_HEIGHT)) {
				onClose();
				return true;
			}
			return false;
		}
		int y = CONTENT_TOP + LINE * 3 + 12;
		if (within(mx, my, CONTENT_X, y, buttonWidth("Open browser again"), BUTTON_HEIGHT)) {
			openBrowser();
			return true;
		}
		int copyX = CONTENT_X + buttonWidth("Open browser again") + BUTTON_GAP;
		if (within(mx, my, copyX, y, buttonWidth("Copy link"), BUTTON_HEIGHT)) {
			minecraft.keyboardHandler.setClipboard(authorizeUrl.toString());
			return true;
		}
		int cancelX = copyX + buttonWidth("Copy link") + BUTTON_GAP;
		if (within(mx, my, cancelX, y, buttonWidth("Cancel"), BUTTON_HEIGHT)) {
			onClose();
			return true;
		}
		return clickedFallbackLink(mx, my, y + BUTTON_HEIGHT + 16);
	}

	private boolean clickedDeviceCode(double mx, double my) {
		if (code == null) {
			if (within(mx, my, CONTENT_X, CONTENT_TOP + 20, buttonWidth("Cancel"), BUTTON_HEIGHT)) {
				onClose();
				return true;
			}
			return false;
		}
		int y = CONTENT_TOP + 18 + CODE_BOX_HEIGHT + 12;
		if (within(mx, my, CONTENT_X, y, buttonWidth("Copy code"), BUTTON_HEIGHT)) {
			copyCode();
			return true;
		}
		int openX = CONTENT_X + buttonWidth("Copy code") + BUTTON_GAP;
		if (within(mx, my, openX, y, buttonWidth("Open browser"), BUTTON_HEIGHT)) {
			openBrowser();
			return true;
		}
		int cancelX = openX + buttonWidth("Open browser") + BUTTON_GAP;
		if (within(mx, my, cancelX, y, buttonWidth("Cancel"), BUTTON_HEIGHT)) {
			onClose();
			return true;
		}
		return false;
	}

	/** The "use a code instead" text is a hit target, not decoration. */
	private boolean clickedFallbackLink(double mx, double my, int y) {
		String text = "No browser on this machine? Use a code instead.";
		if (!within(mx, my, CONTENT_X, y - 2, font.width(text), LINE)) {
			return false;
		}
		mode = Mode.DEVICE_CODE;
		retry();
		return true;
	}

	/**
	 * Abandons whatever is running and starts the current mode fresh. The cancel flag has to be
	 * lowered again afterwards, or the new attempt would see the old one's cancellation.
	 */
	private void retry() {
		cancelled.set(true);
		cancelled.set(false);
		authorizeUrl = null;
		code = null;
		error = "";
		submitting = false;
		pasteBuffer.setLength(0);
		start();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == KEY_ESCAPE) {
			onClose();
			return true;
		}
		if (mode == Mode.MANUAL && error.isEmpty()) {
			// Ctrl+V is the whole point of this field — the thing being entered is a 700-character
			// URL nobody is going to type. The field is always focused, since it is the only input
			// on the screen.
			if (event.hasControlDown() && event.key() == InputConstants.KEY_V) {
				append(minecraft.keyboardHandler.getClipboard());
				return true;
			}
			if (event.key() == InputConstants.KEY_BACKSPACE) {
				if (event.hasControlDown()) {
					pasteBuffer.setLength(0);
				} else if (!pasteBuffer.isEmpty()) {
					pasteBuffer.deleteCharAt(pasteBuffer.length() - 1);
				}
				return true;
			}
			if (event.key() == InputConstants.KEY_RETURN) {
				submit();
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (mode == Mode.MANUAL && error.isEmpty()) {
			append(event.codepointAsString());
			return true;
		}
		return super.charTyped(event);
	}

	private void append(String text) {
		if (text == null) {
			return;
		}
		// Newlines arrive when a URL is copied out of a text editor rather than an address bar, and
		// a stray one in the middle of a token is a confusing failure later rather than here.
		String cleaned = text.replaceAll("\\s", "");
		int room = MAX_PASTE_LENGTH - pasteBuffer.length();
		if (room > 0 && !cleaned.isEmpty()) {
			pasteBuffer.append(cleaned, 0, Math.min(room, cleaned.length()));
		}
	}

	@Override
	public void onClose() {
		// Stops the worker and closes the loopback listener. Without this, walking away leaves a
		// port open and a sign-in running for the full timeout.
		cancelled.set(true);
		Minecraft.getInstance().setScreenAndShow(parent);
	}

	/** Stops a module keybind firing into the paste field while a URL is being entered. */
	@Override
	public boolean isCapturingTextInput() {
		return mode == Mode.MANUAL && error.isEmpty();
	}

	private static boolean within(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}

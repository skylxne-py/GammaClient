package dev.gamma.gui.clickgui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.gamma.config.ConfigManager;
import dev.gamma.core.GammaExecutor;
import dev.gamma.core.ModuleRegistry;
import dev.gamma.core.keybind.TextInputCapture;
import dev.gamma.render.Renderer2D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE;

/**
 * Named config management, reached from the ClickGUI's Configs button the same way the HUD editor
 * is reached from its own.
 *
 * <p>This exists because the automatic per-server profiles underneath it are not what people mean
 * by "my config". Profiles follow the server you're on, which is the right default and also the
 * reason a setup built in singleplayer appears to vanish on joining a server — the server has its
 * own profile and loading it is correct. A named config is the explicit half: a snapshot you name,
 * that loads into any world on demand. Both use the same on-disk format, so a config is just a
 * profile you chose the filename for.
 *
 * <p>Loading applies to the live modules and deliberately does not write anything back. The active
 * profile picks the new state up at its own next save, so loading a config in one world never
 * rewrites another world's profile behind your back.
 */
public final class ConfigsScreen extends Screen implements TextInputCapture {

	private static final int LIST_X = 40;
	private static final int LIST_TOP = 76;
	private static final int LIST_WIDTH = 280;
	private static final int ROW_HEIGHT = 34;
	private static final int SERVER_FIELD_HEIGHT = 13;
	private static final int ROW_GAP = 4;
	private static final int FIELD_HEIGHT = 20;
	private static final int BUTTON_GAP = 6;
	private static final int BUTTON_PADDING = 10;
	private static final int MAX_ADDRESS_LENGTH = 128;
	/** Caret blink period. Purely a "this takes typing" cue -- the field looked inert without one. */
	private static final long CARET_PERIOD_MILLIS = 1_060;

	private final ConfigManager configManager;
	private final ModuleRegistry registry;
	private final Theme theme;
	private final Screen parent;

	private final StringBuilder nameBuffer = new StringBuilder();
	private boolean nameFocused;
	private List<String> configs = List.of();
	/** Config name to the server address it is bound to. Loaded with the list, since reading it is I/O. */
	private Map<String, String> servers = Map.of();
	/** The config whose server field is being edited, or null. */
	private String editingServer;
	private final StringBuilder serverBuffer = new StringBuilder();
	private String status = "";

	public ConfigsScreen(ConfigManager configManager, ModuleRegistry registry, Theme theme, Screen parent) {
		super(Component.literal("Gamma Configs"));
		this.configManager = configManager;
		this.registry = registry;
		this.theme = theme;
		this.parent = parent;
	}

	@Override
	protected void init() {
		refresh();
	}

	private void refresh() {
		configManager.listConfigsAsync(names -> {
			configs = names;
			loadServerBindings(names);
		});
	}

	/** Reading each binding means opening each config file, so it happens once per refresh, off-thread. */
	private void loadServerBindings(List<String> names) {
		GammaExecutor.execute(() -> {
			Map<String, String> found = new HashMap<>();
			for (String name : names) {
				String address = configManager.configServer(name);
				if (!address.isEmpty()) {
					found.put(name, address);
				}
			}
			Minecraft.getInstance().execute(() -> servers = found);
		});
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		Renderer2D renderer = new Renderer2D(extractor);
		renderer.setTextShadow(false);
		renderer.fill(0, 0, width, height, 0x99000000);

		renderer.text(font, "Configs", LIST_X, 28, theme.textPrimary());
		renderer.text(font, "Saved setups you can load into any world. Esc to go back.", LIST_X, 44, theme.textSecondary());

		renderNameField(renderer, mouseX, mouseY);

		int y = LIST_TOP + FIELD_HEIGHT + 14;
		if (configs.isEmpty()) {
			renderer.text(font, "No saved configs yet — type a name above and hit Save.", LIST_X, y + 6, theme.textSecondary());
		}
		for (String name : configs) {
			renderRow(renderer, name, y, mouseX, mouseY);
			y += ROW_HEIGHT + ROW_GAP;
		}

		if (!status.isEmpty()) {
			renderer.text(font, status, LIST_X, height - 20, theme.textSecondary());
		}
	}

	private void renderNameField(Renderer2D renderer, int mouseX, int mouseY) {
		renderer.roundedRect(LIST_X, LIST_TOP, LIST_WIDTH, FIELD_HEIGHT, 5, theme.settingsBackground());
		if (nameFocused) {
			renderer.roundedRectOutline(LIST_X, LIST_TOP, LIST_WIDTH, FIELD_HEIGHT, 5, 1.5f, theme.accent());
		}
		String shown = nameBuffer.isEmpty() && !nameFocused ? "Config name..." : nameBuffer + caret(nameFocused);
		int textColor = nameBuffer.isEmpty() && !nameFocused ? theme.textSecondary() : theme.textPrimary();
		renderer.text(font, shown, LIST_X + 6, LIST_TOP + (FIELD_HEIGHT - font.lineHeight) / 2, textColor);

		int saveX = LIST_X + LIST_WIDTH + BUTTON_GAP;
		renderButton(renderer, "Save", saveX, LIST_TOP, FIELD_HEIGHT, mouseX, mouseY);
	}

	private void renderRow(Renderer2D renderer, String name, int y, int mouseX, int mouseY) {
		boolean hovered = mouseX >= LIST_X && mouseX < LIST_X + LIST_WIDTH && mouseY >= y && mouseY < y + ROW_HEIGHT;
		renderer.roundedRect(LIST_X, y, LIST_WIDTH, ROW_HEIGHT, 5, hovered ? theme.rowHoverBackground() : theme.settingsBackground());
		renderer.text(font, name, LIST_X + 8, y + 4, theme.textPrimary());

		// The binding, as an inline field on its own line. A config that auto-loads on a particular
		// server should say so where you look for the config, not in a separate screen.
		int fieldY = y + ROW_HEIGHT - SERVER_FIELD_HEIGHT - 4;
		int fieldWidth = LIST_WIDTH - 16;
		boolean editing = name.equals(editingServer);
		renderer.roundedRect(LIST_X + 8, fieldY, fieldWidth, SERVER_FIELD_HEIGHT, 4, 0x33000000);
		if (editing) {
			renderer.roundedRectOutline(LIST_X + 8, fieldY, fieldWidth, SERVER_FIELD_HEIGHT, 4, 1f, theme.accent());
		}
		String bound = servers.getOrDefault(name, "");
		String shown = editing ? serverBuffer + caret(true) : bound;
		// Never the placeholder while editing: clearing the field would otherwise flash "click to
		// auto-load on one" back at you on every caret blink.
		boolean placeholder = !editing && bound.isEmpty();
		renderer.text(font, placeholder ? "No server — click to auto-load on one" : shown,
				LIST_X + 12, fieldY + (SERVER_FIELD_HEIGHT - font.lineHeight) / 2,
				placeholder ? theme.textSecondary() : theme.accent());

		int loadX = LIST_X + LIST_WIDTH + BUTTON_GAP;
		renderButton(renderer, "Load", loadX, y, ROW_HEIGHT, mouseX, mouseY);
		int deleteX = loadX + buttonWidth("Load") + BUTTON_GAP;
		renderButton(renderer, "Delete", deleteX, y, ROW_HEIGHT, mouseX, mouseY);
		renderButton(renderer, "Set IP", deleteX + buttonWidth("Delete") + BUTTON_GAP, y, ROW_HEIGHT, mouseX, mouseY);
	}

	/** The address of the server currently connected to, or empty in singleplayer / the main menu. */
	private static String currentServerAddress() {
		Minecraft client = Minecraft.getInstance();
		if (client.hasSingleplayerServer()) {
			return "";
		}
		ServerData server = client.getCurrentServer();
		return server == null ? "" : server.ip;
	}

	private void renderButton(Renderer2D renderer, String label, int x, int y, int rowHeight, int mouseX, int mouseY) {
		int w = buttonWidth(label);
		boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + rowHeight;
		renderer.roundedRect(x, y, w, rowHeight, 5, hovered ? theme.rowHoverBackground() : theme.settingsBackground());
		renderer.text(font, label, x + BUTTON_PADDING / 2, y + (rowHeight - font.lineHeight) / 2, theme.textPrimary());
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

		if (within(mx, my, LIST_X, LIST_TOP, LIST_WIDTH, FIELD_HEIGHT)) {
			nameFocused = true;
			return true;
		}
		nameFocused = false;

		int saveX = LIST_X + LIST_WIDTH + BUTTON_GAP;
		if (within(mx, my, saveX, LIST_TOP, buttonWidth("Save"), FIELD_HEIGHT)) {
			save();
			return true;
		}

		int y = LIST_TOP + FIELD_HEIGHT + 14;
		for (String name : configs) {
			int loadX = LIST_X + LIST_WIDTH + BUTTON_GAP;
			if (within(mx, my, loadX, y, buttonWidth("Load"), ROW_HEIGHT)) {
				commitServerEdit();
				configManager.loadConfig(name, registry);
				status = "Loaded '" + name + "'";
				return true;
			}
			int deleteX = loadX + buttonWidth("Load") + BUTTON_GAP;
			if (within(mx, my, deleteX, y, buttonWidth("Delete"), ROW_HEIGHT)) {
				commitServerEdit();
				configManager.deleteConfig(name, deleted -> {
					status = deleted ? "Deleted '" + name + "'" : "Could not delete '" + name + "'";
					refresh();
				});
				return true;
			}
			int setIpX = deleteX + buttonWidth("Delete") + BUTTON_GAP;
			if (within(mx, my, setIpX, y, buttonWidth("Set IP"), ROW_HEIGHT)) {
				String address = currentServerAddress();
				if (address.isEmpty()) {
					status = "Not connected to a multiplayer server";
					return true;
				}
				bindServer(name, address);
				return true;
			}
			int fieldY = y + ROW_HEIGHT - SERVER_FIELD_HEIGHT - 4;
			if (within(mx, my, LIST_X + 8, fieldY, LIST_WIDTH - 16, SERVER_FIELD_HEIGHT)) {
				commitServerEdit();
				editingServer = name;
				serverBuffer.setLength(0);
				serverBuffer.append(servers.getOrDefault(name, ""));
				return true;
			}
			// Clicking the row itself puts its name in the field, so overwriting an existing
			// config is retyping nothing rather than retyping it exactly.
			if (within(mx, my, LIST_X, y, LIST_WIDTH, ROW_HEIGHT)) {
				commitServerEdit();
				nameBuffer.setLength(0);
				nameBuffer.append(name);
				return true;
			}
			y += ROW_HEIGHT + ROW_GAP;
		}
		commitServerEdit();
		return false;
	}

	private void bindServer(String name, String address) {
		configManager.setConfigServer(name, address, () -> {
			status = address.isEmpty()
					? "'" + name + "' no longer auto-loads on any server"
					: "'" + name + "' will auto-load on " + address;
			refresh();
		});
	}

	/** Writes an in-progress server edit, if there is one. Called before anything that changes focus. */
	private void commitServerEdit() {
		if (editingServer == null) {
			return;
		}
		String name = editingServer;
		editingServer = null;
		bindServer(name, serverBuffer.toString().trim());
	}

	private static String caret(boolean focused) {
		return focused && System.currentTimeMillis() % CARET_PERIOD_MILLIS < CARET_PERIOD_MILLIS / 2 ? "_" : "";
	}

	private static boolean within(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	private void save() {
		String name = nameBuffer.toString().trim();
		if (!ConfigManager.isValidConfigName(name)) {
			status = "Name must be 1-48 characters: letters, digits, . - and _ only";
			return;
		}
		configManager.saveConfig(name, registry);
		status = "Saved '" + name + "'";
		refresh();
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		String s = event.codepointAsString();
		if (s.isBlank()) {
			return nameFocused || editingServer != null;
		}
		if (nameFocused) {
			if (nameBuffer.length() < 48) {
				nameBuffer.append(s);
			}
			return true;
		}
		if (editingServer != null) {
			if (serverBuffer.length() < MAX_ADDRESS_LENGTH) {
				serverBuffer.append(s);
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (nameFocused) {
			if (event.key() == InputConstants.KEY_BACKSPACE && nameBuffer.length() > 0) {
				nameBuffer.deleteCharAt(nameBuffer.length() - 1);
				return true;
			}
			if (event.key() == InputConstants.KEY_RETURN) {
				save();
				return true;
			}
			if (event.key() == KEY_ESCAPE) {
				nameFocused = false;
				return true;
			}
			return true;
		}
		if (editingServer != null) {
			if (event.key() == InputConstants.KEY_BACKSPACE && serverBuffer.length() > 0) {
				serverBuffer.deleteCharAt(serverBuffer.length() - 1);
				return true;
			}
			if (event.key() == InputConstants.KEY_RETURN) {
				commitServerEdit();
				return true;
			}
			// Escape discards, unlike clicking away or Enter, which both commit. Escape backing out of
			// an edit unchanged is what it means everywhere else, and this field is one keystroke away
			// from pointing a config at the wrong server.
			if (event.key() == KEY_ESCAPE) {
				editingServer = null;
				serverBuffer.setLength(0);
				return true;
			}
			return true;
		}
		if (event.key() == KEY_ESCAPE) {
			Minecraft.getInstance().gui.setScreen(parent);
			return true;
		}
		return super.keyPressed(event);
	}

	/** Both fields, not just the name — the keybind suspension has to cover whichever one is taking keystrokes. */
	@Override
	public boolean isCapturingTextInput() {
		return nameFocused || editingServer != null;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}

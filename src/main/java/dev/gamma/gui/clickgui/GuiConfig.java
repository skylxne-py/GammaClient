package dev.gamma.gui.clickgui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.gamma.Gamma;
import dev.gamma.core.Category;
import dev.gamma.core.GammaExecutor;
import dev.gamma.core.GammaPaths;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Client-wide (not per-server) ClickGUI preferences: the theme accent color, the active
 * the category tab last open. Stored at {@code gamma/gui.json}, off the client
 * thread, same debounce-on-write pattern as {@link dev.gamma.config.ConfigManager}.
 */
public final class GuiConfig {

	private static final long SAVE_DEBOUNCE_MS = 500;

	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final Map<Category, PanelLayout> panels = new EnumMap<>(Category.class);
	private int accent = Theme.DEFAULT_ACCENT;
	/** The category tab last open in the menu, so reopening lands where you left off. */
	private Category lastCategory = Category.values()[0];
	private volatile ScheduledFuture<?> pendingSave;

	public record PanelLayout(double x, double y, boolean collapsed) {
	}

	public int accent() {
		return accent;
	}

	public void setAccent(int accent) {
		this.accent = accent;
		requestSave();
	}

	public Category lastCategory() {
		return lastCategory;
	}

	public void setLastCategory(Category lastCategory) {
		if (lastCategory == this.lastCategory) {
			return;
		}
		this.lastCategory = lastCategory;
		requestSave();
	}

	public PanelLayout panelLayout(Category category, double defaultX, double defaultY) {
		return panels.getOrDefault(category, new PanelLayout(defaultX, defaultY, false));
	}

	public void setPanelLayout(Category category, PanelLayout layout) {
		panels.put(category, layout);
		requestSave();
	}

	/**
	 * Reads off the client thread, then applies the result back on it once — call once at
	 * startup. {@code theme} is pushed the loaded accent directly since {@link Theme} is the
	 * render-facing copy of the same value this class persists.
	 */
	public void load(Theme theme) {
		GammaExecutor.execute(() -> {
			JsonObject root = readJson(path());
			Minecraft.getInstance().execute(() -> apply(root, theme));
		});
	}

	private void apply(JsonObject root, Theme theme) {
		if (root == null) {
			return;
		}
		if (root.has("accent")) {
			accent = root.get("accent").getAsInt();
			theme.setAccent(accent);
		}
		if (root.has("lastCategory")) {
			try {
				lastCategory = Category.valueOf(root.get("lastCategory").getAsString());
			} catch (IllegalArgumentException e) {
				Gamma.LOGGER.warn("gui.json names unknown category {}, keeping {}", root.get("lastCategory"), lastCategory);
			}
		}
		if (root.has("panels")) {
			JsonObject panelsJson = root.getAsJsonObject("panels");
			for (String key : panelsJson.keySet()) {
				try {
					Category category = Category.valueOf(key);
					JsonObject layout = panelsJson.getAsJsonObject(key);
					panels.put(category, new PanelLayout(
							layout.get("x").getAsDouble(),
							layout.get("y").getAsDouble(),
							layout.has("collapsed") && layout.get("collapsed").getAsBoolean()));
				} catch (IllegalArgumentException e) {
					Gamma.LOGGER.warn("gui.json references unknown category '{}', skipping", key);
				}
			}
		}
	}

	private void requestSave() {
		ScheduledFuture<?> previous = pendingSave;
		if (previous != null) {
			previous.cancel(false);
		}
		JsonObject snapshot = snapshot();
		pendingSave = GammaExecutor.schedule(() -> writeJson(path(), snapshot), SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
	}

	private JsonObject snapshot() {
		JsonObject root = new JsonObject();
		root.addProperty("accent", accent);
		root.addProperty("lastCategory", lastCategory.name());
		JsonObject panelsJson = new JsonObject();
		for (Map.Entry<Category, PanelLayout> entry : panels.entrySet()) {
			JsonObject layout = new JsonObject();
			layout.addProperty("x", entry.getValue().x());
			layout.addProperty("y", entry.getValue().y());
			layout.addProperty("collapsed", entry.getValue().collapsed());
			panelsJson.add(entry.getKey().name(), layout);
		}
		root.add("panels", panelsJson);
		return root;
	}

	private Path path() {
		return GammaPaths.root().resolve("gui.json");
	}

	private JsonObject readJson(Path file) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			return gson.fromJson(reader, JsonObject.class);
		} catch (IOException | JsonParseException e) {
			Gamma.LOGGER.error("Failed to read {}, ignoring", file, e);
			return null;
		}
	}

	private void writeJson(Path file, JsonObject root) {
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			gson.toJson(root, writer);
		} catch (IOException e) {
			Gamma.LOGGER.error("Failed to write {}", file, e);
		}
	}
}

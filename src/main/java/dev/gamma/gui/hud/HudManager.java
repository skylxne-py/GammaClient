package dev.gamma.gui.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.gamma.Gamma;
import dev.gamma.config.GammaSettings;
import dev.gamma.core.GammaExecutor;
import dev.gamma.core.GammaPaths;
import dev.gamma.core.ModuleRegistry;
import dev.gamma.core.event.EventBus;
import dev.gamma.gui.hud.elements.ArmorElement;
import dev.gamma.gui.hud.elements.BiomeElement;
import dev.gamma.gui.hud.elements.CoordinatesElement;
import dev.gamma.gui.hud.elements.DirectionElement;
import dev.gamma.gui.hud.elements.FpsElement;
import dev.gamma.gui.hud.elements.InventoryViewerElement;
import dev.gamma.gui.hud.elements.MapMinimapElement;
import dev.gamma.gui.hud.elements.ModuleListElement;
import dev.gamma.gui.hud.elements.PingElement;
import dev.gamma.gui.hud.elements.PotionEffectsElement;
import dev.gamma.gui.hud.elements.SessionTimeElement;
import dev.gamma.gui.hud.elements.ShardItemTimerElement;
import dev.gamma.gui.hud.elements.SpotifyElement;
import dev.gamma.gui.hud.elements.TpsElement;
import dev.gamma.gui.hud.elements.WatermarkElement;
import dev.gamma.render.Renderer2D;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns every {@link HudComponent}, persists their layout to {@code gamma/hud.json} (client-wide,
 * not per-server — a HUD layout is a screen-space preference, not per-world state), and bridges
 * them onto Fabric's single HUD element ({@code HudElementRegistry}) as one registered layer.
 */
public final class HudManager implements HudElement {

	private static final long SAVE_DEBOUNCE_MS = 500;
	private static final Identifier LAYER_ID = Identifier.fromNamespaceAndPath(Gamma.MOD_ID, "hud");

	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final List<HudComponent> components = new ArrayList<>();
	private final TpsTracker tpsTracker;
	private final long sessionStartMillis = System.currentTimeMillis();
	private volatile ScheduledFuture<?> pendingSave;

	public HudManager(ModuleRegistry moduleRegistry, EventBus eventBus) {
		this.tpsTracker = new TpsTracker(eventBus);
		registerAll(moduleRegistry);
	}

	private void registerAll(ModuleRegistry moduleRegistry) {
		components.add(new WatermarkElement());
		components.add(new ModuleListElement(moduleRegistry));
		components.add(new FpsElement());
		components.add(new CoordinatesElement());
		components.add(new DirectionElement());
		components.add(new BiomeElement());
		components.add(new PingElement());
		components.add(new TpsElement());
		components.add(new SessionTimeElement());
		components.add(new ArmorElement());
		components.add(new PotionEffectsElement());
		components.add(new InventoryViewerElement());
		components.add(new MapMinimapElement());
		components.add(new ShardItemTimerElement());
		components.add(new SpotifyElement());
	}

	public List<HudComponent> all() {
		return components;
	}

	public void install() {
		HudElementRegistry.addLast(LAYER_ID, this);
	}

	public void requestSave() {
		ScheduledFuture<?> previous = pendingSave;
		if (previous != null) {
			previous.cancel(false);
		}
		JsonObject snapshot = snapshot();
		pendingSave = GammaExecutor.schedule(() -> writeJson(path(), snapshot), SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
	}

	public void load() {
		GammaExecutor.execute(() -> {
			JsonObject root = readJson(path());
			Minecraft.getInstance().execute(() -> apply(root));
		});
	}

	private void apply(JsonObject root) {
		if (root == null) {
			return;
		}
		for (HudComponent component : components) {
			if (!root.has(component.id())) {
				continue;
			}
			JsonObject json = root.getAsJsonObject(component.id());
			component.setEnabled(!json.has("enabled") || json.get("enabled").getAsBoolean());
			try {
				Anchor anchor = json.has("anchor") ? Anchor.valueOf(json.get("anchor").getAsString()) : component.anchor();
				double offsetX = json.has("offsetX") ? json.get("offsetX").getAsDouble() : component.offsetX();
				double offsetY = json.has("offsetY") ? json.get("offsetY").getAsDouble() : component.offsetY();
				component.setPosition(anchor, offsetX, offsetY);
			} catch (IllegalArgumentException e) {
				Gamma.LOGGER.warn("hud.json: unknown anchor for '{}', keeping default", component.id());
			}
			if (json.has("scale")) {
				component.setScale(json.get("scale").getAsDouble());
			}
			if (json.has("color")) {
				component.setColor(json.get("color").getAsInt());
			}
		}
	}

	private JsonObject snapshot() {
		JsonObject root = new JsonObject();
		for (HudComponent component : components) {
			JsonObject json = new JsonObject();
			json.addProperty("enabled", component.isEnabled());
			json.addProperty("anchor", component.anchor().name());
			json.addProperty("offsetX", component.offsetX());
			json.addProperty("offsetY", component.offsetY());
			json.addProperty("scale", component.scale());
			json.addProperty("color", component.color());
			root.add(component.id(), json);
		}
		return root;
	}

	private Path path() {
		return GammaPaths.root().resolve("hud.json");
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

	/** Built once per frame and reused by {@link HudEditorScreen} so its preview matches live rendering exactly. */
	public HudContext buildContext(int screenWidth, int screenHeight, float partialTick) {
		Minecraft client = Minecraft.getInstance();
		return new HudContext(client, client.player, client.level, screenWidth, screenHeight, partialTick, tpsTracker.currentTps(), sessionStartMillis);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor extractor, DeltaTracker deltaTracker) {
		HudContext ctx = buildContext(extractor.guiWidth(), extractor.guiHeight(), deltaTracker.getGameTimeDeltaPartialTick(false));
		Renderer2D renderer = new Renderer2D(extractor);
		renderer.setTextShadow(false);
		// The master switch hides the elements without touching their own enabled flags, so turning
		// it back on restores the layout you had rather than an empty screen you have to rebuild.
		if (GammaSettings.hudEnabled()) {
			for (HudComponent component : components) {
				if (!component.isEnabled()) {
					continue;
				}
				renderComponent(extractor, renderer, ctx.client().font, component, ctx);
			}
		}
		// Last, so it draws over the HUD elements rather than under them. Not a HudComponent: it is
		// transient and belongs directly above the hotbar, so there is nothing for the HUD editor to
		// position and nothing on screen to grab when no find has happened.
		Notifications.render(extractor, renderer, ctx.client().font, ctx);
	}

	/** Package-visible so {@link HudEditorScreen} can reuse the exact same layout math for its drag/preview pass. */
	static void renderComponent(GuiGraphicsExtractor extractor, Renderer2D renderer, net.minecraft.client.gui.Font font, HudComponent component, HudContext ctx) {
		Bounds bounds = resolveBounds(component, font, ctx);

		var pose = extractor.pose();
		pose.pushMatrix();
		pose.translate((float) bounds.x(), (float) bounds.y());
		pose.scale((float) component.scale());
		component.render(renderer, font, 0, 0, ctx);
		pose.popMatrix();
	}

	/** The resolved (anchor + offset, scaled) screen-space box a component occupies this frame — shared with {@link HudEditorScreen} for hit-testing and snapping. */
	public record Bounds(int x, int y, int width, int height) {
	}

	public static Bounds resolveBounds(HudComponent component, net.minecraft.client.gui.Font font, HudContext ctx) {
		int measuredWidth = Math.max(1, component.measureWidth(font, ctx));
		int measuredHeight = Math.max(1, component.measureHeight(font, ctx));
		double scale = component.scale();
		int scaledWidth = (int) Math.round(measuredWidth * scale);
		int scaledHeight = (int) Math.round(measuredHeight * scale);
		int x = component.anchor().resolveX(ctx.screenWidth(), scaledWidth, component.offsetX());
		int y = component.anchor().resolveY(ctx.screenHeight(), scaledHeight, component.offsetY());
		return new Bounds(x, y, scaledWidth, scaledHeight);
	}
}

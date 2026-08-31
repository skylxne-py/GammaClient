package dev.gamma.waypoints;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.gamma.Gamma;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Third-party waypoint importers. Both formats below were checked against real documentation/community references before writing a
 * parser, rather than assumed — see the design notes for the sources and, for
 * Lunar's format specifically, the honest caveat about what wasn't independently confirmable in
 * this environment.
 */
public final class WaypointImportExport {

	private static final Gson GSON = new Gson();

	/**
	 * Xaero's Minimap {@code waypoint:} line format, e.g.
	 * {@code waypoint:Farm:F:-3155:137:5230:11:false:0:gui.xaero_default:false:0} — name,
	 * single-letter initial, x, y, z, a 0-15 color-index, a disabled flag, then several fields
	 * Xaero itself doesn't document further (kept but ignored here). Dimension isn't in the line
	 * itself — Xaero encodes it in the containing folder name ({@code dim%0} overworld, {@code
	 * dim%-1} nether, {@code dim%1} end), so callers pass it in directly rather than this method
	 * guessing from a path that may not even be the real Xaero folder layout.
	 */
	public static List<Waypoint> importXaero(Path file, String dimension) {
		List<Waypoint> result = new ArrayList<>();
		List<String> lines;
		try {
			lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			Gamma.LOGGER.error("Failed to read Xaero waypoints file {}", file, e);
			return result;
		}
		for (String line : lines) {
			if (!line.startsWith("waypoint:")) {
				continue;
			}
			String[] parts = line.split(":", -1);
			if (parts.length < 8) {
				continue;
			}
			try {
				String name = parts[1];
				int x = Integer.parseInt(parts[3]);
				int y = Integer.parseInt(parts[4]);
				int z = Integer.parseInt(parts[5]);
				int colorIndex = Integer.parseInt(parts[6]);
				boolean disabled = Boolean.parseBoolean(parts[7]);
				result.add(new Waypoint(WaypointStore.newId(), name, WaypointCategory.MISC, dimension, x, y, z,
						xaeroColor(colorIndex), null, true, !disabled, System.currentTimeMillis(), WaypointSource.IMPORT));
			} catch (NumberFormatException e) {
				Gamma.LOGGER.warn("Skipping unparseable Xaero waypoint line: {}", line);
			}
		}
		return result;
	}

	/** Xaero's fixed 16-color waypoint palette mirrors vanilla's chat/dye color values closely enough to reuse them — not shipped by Xaero as data we can read, so this is the standard mapping other converters use. */
	private static int xaeroColor(int index) {
		int[] palette = {
				0xFFFFFFFF, 0xFFFFAA00, 0xFFFF55FF, 0xFF55FFFF,
				0xFFFFFF55, 0xFF55FF55, 0xFFFF5555, 0xFFAAAAAA,
				0xFF555555, 0xFF5555FF, 0xFFAA00AA, 0xFF00AAAA,
				0xFFAAAA00, 0xFF00AA00, 0xFFAA0000, 0xFF000000,
		};
		return palette[Math.floorMod(index, palette.length)];
	}

	/**
	 * Lunar Client's {@code waypoints.json}: confirmed (Apollo mod-docs) that a waypoint object
	 * carries {@code name}, a {@code location} with {@code world}/{@code x}/{@code y}/{@code z},
	 * and a {@code color}; NOT independently confirmed here is the exact on-disk nesting depth
	 * (server key -> world key -> waypoint name), since no real Lunar install was reachable in
	 * this environment to inspect. This parser is deliberately shape-tolerant rather than
	 * position-tolerant: it walks the whole JSON tree looking for objects that carry recognizable
	 * waypoint fields (either flat {@code x}/{@code y}/{@code z}, or nested under {@code
	 * location}), so it survives being wrong about the exact nesting. Flag any real-world import
	 * mismatch back so the shape can be corrected against an actual file.
	 */
	public static List<Waypoint> importLunar(Path file, String defaultDimension) {
		List<Waypoint> result = new ArrayList<>();
		JsonElement root;
		try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			root = GSON.fromJson(reader, JsonElement.class);
		} catch (IOException | JsonParseException e) {
			Gamma.LOGGER.error("Failed to read Lunar waypoints file {}", file, e);
			return result;
		}
		if (root != null) {
			walkLunar(root, null, defaultDimension, result);
		}
		return result;
	}

	private static void walkLunar(JsonElement element, String nameHint, String defaultDimension, List<Waypoint> out) {
		if (element.isJsonObject()) {
			JsonObject o = element.getAsJsonObject();
			JsonObject coords = o.has("location") && o.get("location").isJsonObject() ? o.getAsJsonObject("location") : o;
			if (coords.has("x") && coords.has("y") && coords.has("z")) {
				String name = o.has("name") ? o.get("name").getAsString() : (nameHint != null ? nameHint : "Imported");
				double x = coords.get("x").getAsDouble();
				double y = coords.get("y").getAsDouble();
				double z = coords.get("z").getAsDouble();
				Integer color = parseLunarColor(o.has("color") ? o.get("color") : null);
				String dimension = coords.has("world") ? normalizeLunarWorld(coords.get("world").getAsString(), defaultDimension) : defaultDimension;
				out.add(new Waypoint(WaypointStore.newId(), name, WaypointCategory.MISC, dimension, x, y, z,
						color, null, true, true, System.currentTimeMillis(), WaypointSource.IMPORT));
				return;
			}
			for (var entry : o.entrySet()) {
				walkLunar(entry.getValue(), entry.getKey(), defaultDimension, out);
			}
		} else if (element.isJsonArray()) {
			for (JsonElement child : element.getAsJsonArray()) {
				walkLunar(child, nameHint, defaultDimension, out);
			}
		}
	}

	private static Integer parseLunarColor(JsonElement colorElement) {
		if (colorElement == null) {
			return null;
		}
		if (colorElement.isJsonPrimitive() && colorElement.getAsJsonPrimitive().isNumber()) {
			return colorElement.getAsInt() | 0xFF000000;
		}
		if (colorElement.isJsonObject()) {
			JsonObject c = colorElement.getAsJsonObject();
			if (c.has("r") && c.has("g") && c.has("b")) {
				int r = c.get("r").getAsInt();
				int g = c.get("g").getAsInt();
				int b = c.get("b").getAsInt();
				return 0xFF000000 | (r << 16) | (g << 8) | b;
			}
		}
		return null;
	}

	private static final Pattern DIM_SUFFIX = Pattern.compile(".*(overworld|nether|the_end|end)$", Pattern.CASE_INSENSITIVE);

	private static String normalizeLunarWorld(String world, String defaultDimension) {
		Matcher matcher = DIM_SUFFIX.matcher(world);
		if (!matcher.matches()) {
			return defaultDimension;
		}
		return switch (matcher.group(1).toLowerCase()) {
			case "nether" -> DimensionConversion.NETHER;
			case "the_end", "end" -> "minecraft:the_end";
			default -> DimensionConversion.OVERWORLD;
		};
	}

	private WaypointImportExport() {
	}
}

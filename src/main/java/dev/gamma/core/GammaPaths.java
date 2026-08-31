package dev.gamma.core;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared {@code .minecraft/gamma/} layout — config profiles here, the Phase 5 chunk DBs under {@code chunks/}. */
public final class GammaPaths {

	private GammaPaths() {
	}

	public static Path root() {
		return FabricLoader.getInstance().getGameDir().resolve("gamma");
	}

	public static Path dir(String... segments) {
		Path path = root();
		for (String segment : segments) {
			path = path.resolve(segment);
		}
		ensureDirectory(path);
		return path;
	}

	/** Turns a server address or other free-form key into a safe file name (no extension). */
	public static String sanitizeFileName(String key) {
		return key.replaceAll("[^a-zA-Z0-9 ()._-]", "_");
	}

	private static void ensureDirectory(Path path) {
		try {
			Files.createDirectories(path);
		} catch (IOException e) {
			throw new java.io.UncheckedIOException("Failed to create Gamma directory: " + path, e);
		}
	}
}

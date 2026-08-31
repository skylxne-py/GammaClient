package dev.gamma.chunks;

import com.google.gson.Gson;
import dev.gamma.chunks.model.ChunkObservation;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads a {@code src/test/resources/fixtures/*.json} dump straight into a {@link ChunkObservation}. */
final class FixtureLoader {

	private static final Gson GSON = new Gson();

	private FixtureLoader() {
	}

	static ChunkObservation load(String resourceName) {
		try (Reader reader = new InputStreamReader(
				requireResource("/fixtures/" + resourceName), StandardCharsets.UTF_8)) {
			return GSON.fromJson(reader, ChunkObservation.class);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static java.io.InputStream requireResource(String path) {
		java.io.InputStream stream = FixtureLoader.class.getResourceAsStream(path);
		if (stream == null) {
			throw new IllegalArgumentException("Missing test fixture: " + path);
		}
		return stream;
	}
}

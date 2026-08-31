package dev.gamma.chunks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.gamma.Gamma;
import dev.gamma.chunks.model.ChunkObservation;
import dev.gamma.chunks.model.ClassificationResult;
import dev.gamma.core.GammaExecutor;
import dev.gamma.core.GammaPaths;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code .chunks record} fixture dump — raw {@link ChunkObservation}s plus the pipeline's own
 * verdict, written as JSON under {@code gamma/chunks/fixtures/<server>/} so a real test corpus
 * can be built from real play. {@link ChunkObservation} is already plain data, so a dumped fixture file
 * doubles as a ready-made {@code src/test/resources/fixtures} input — copy one in verbatim.
 *
 * <p>Lives only while {@code .chunks record} is toggled on — {@link ChunkObservationCollector}
 * holds at most one of these (see {@link ChunkObservationCollector#setFixtureRecorder}); toggling
 * off means calling that with {@code null} rather than anything on this class.
 */
public final class FixtureRecorder {

	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final String server;

	public FixtureRecorder(String server) {
		this.server = server;
	}

	public void record(ChunkObservation observation, ClassificationResult result) {
		GammaExecutor.execute(() -> writeFixture(observation, result));
	}

	private void writeFixture(ChunkObservation observation, ClassificationResult result) {
		Path dir = GammaPaths.dir("chunks", "fixtures", GammaPaths.sanitizeFileName(server));
		String fileName = "%s_%d_%d_%d.json".formatted(
				GammaPaths.sanitizeFileName(observation.dimension()), observation.chunkX(), observation.chunkZ(), observation.loadTimeMillis());
		Path file = dir.resolve(fileName);
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			gson.toJson(new Fixture(observation, result), writer);
		} catch (IOException e) {
			Gamma.LOGGER.error("Failed to write chunk fixture {}", file, e);
		}
	}

	private record Fixture(ChunkObservation observation, ClassificationResult result) {
	}
}

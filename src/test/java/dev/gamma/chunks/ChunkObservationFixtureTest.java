package dev.gamma.chunks;

import dev.gamma.chunks.classifier.BaselineStore;
import dev.gamma.chunks.classifier.ClassifierPipeline;
import dev.gamma.chunks.model.ChunkObservation;
import dev.gamma.chunks.model.Classification;
import dev.gamma.chunks.model.ClassificationResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real classifier pipeline against fixture dumps under {@code src/test/resources/fixtures}
 * so classifier changes cannot regress accuracy silently. No live server capture was available to
 * build a real corpus from (see {@code docs/CLASSIFIERS.md}), so these two fixtures are
 * hand-authored, idealized profiles rather than a genuine server capture — replace/
 * extend them with real {@code .chunks record} output as it becomes available, keeping the same
 * JSON shape ({@link ChunkObservation}'s field names) so this test doesn't need to change.
 */
class ChunkObservationFixtureTest {

	@Test
	void freshlyGeneratedProfileClassifiesAsLikelyNewAgainstASettledBaseline() {
		BaselineStore baselines = new BaselineStore();
		ClassifierPipeline pipeline = ClassifierPipeline.standard();
		warmUpWithTypicalTraffic(pipeline, baselines);

		ChunkObservation newChunk = FixtureLoader.load("likely_new_chunk.json");
		ChunkObservation existingChunk = FixtureLoader.load("likely_existing_chunk.json");

		ClassificationResult newResult = pipeline.classify(newChunk, baselines);
		ClassificationResult existingResult = pipeline.classify(existingChunk, baselines);

		assertEquals(Classification.LIKELY_NEW, newResult.classification());
		assertTrue(newResult.confidence() > existingResult.confidence(),
				"the new-chunk fixture should score a higher new-chunk confidence than the existing-chunk fixture");
	}

	/** Ten unremarkable loads matching the "existing chunk" fixture's scale, so baselines aren't cold when the fixtures are classified. */
	private void warmUpWithTypicalTraffic(ClassifierPipeline pipeline, BaselineStore baselines) {
		long[] latencies = {38, 42, 47, 44, 40, 46, 43, 41, 45, 39};
		for (long latency : latencies) {
			ChunkObservation typical = new ChunkObservation(
					"example.server.net", "minecraft:overworld", 0, 0, 0, latency,
					9000, 40, 0.31, 0, 1, 0, 2,
					Map.of("chest", 1), Map.of(), 0);
			pipeline.classify(typical, baselines);
		}
	}
}

package dev.gamma.chunks.classifier;

import dev.gamma.chunks.model.ChunkObservation;
import dev.gamma.chunks.model.Classification;
import dev.gamma.chunks.model.ClassificationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassifierPipelineTest {

	@Test
	void allClassifiersAbstainingYieldsUnknownAtNeutralConfidence() {
		ClassifierPipeline pipeline = new ClassifierPipeline(java.util.List.of(new UnrolledLootClassifier()));
		ChunkObservation observation = TestObservations.builder().totalContainers(0).build();
		ClassificationResult result = pipeline.classify(observation, new BaselineStore());
		assertEquals(Classification.UNKNOWN, result.classification());
		assertEquals(0.5, result.confidence(), 1e-9);
		assertTrue(result.signals().isEmpty());
	}

	@Test
	void abstainingClassifiersAreExcludedFromSignalsButOthersStillVote() {
		ClassifierPipeline pipeline = new ClassifierPipeline(java.util.List.of(
				new UnrolledLootClassifier(), // abstains: no containers
				new GenerationLatencyClassifier())); // abstains: cold baseline
		ChunkObservation observation = TestObservations.builder().totalContainers(0).requestLatencyMillis(-1).build();
		ClassificationResult result = pipeline.classify(observation, new BaselineStore());
		assertEquals(Classification.UNKNOWN, result.classification());
		assertTrue(result.signals().isEmpty());
	}

	@Test
	void aStronglyNewSignalWinsOutOverANeutralOne() {
		ClassifierPipeline pipeline = new ClassifierPipeline(java.util.List.of(new UnrolledLootClassifier()));
		ChunkObservation observation = TestObservations.builder().totalContainers(4).unrolledLootContainers(4).build();
		ClassificationResult result = pipeline.classify(observation, new BaselineStore());
		assertEquals(Classification.LIKELY_NEW, result.classification());
		assertTrue(result.confidence() > 0.9);
		assertFalse(result.signals().isEmpty());
	}

	@Test
	void aStronglyExistingSignalClassifiesAsExisting() {
		ClassifierPipeline pipeline = new ClassifierPipeline(java.util.List.of(new UnrolledLootClassifier()));
		ChunkObservation observation = TestObservations.builder().totalContainers(4).unrolledLootContainers(0).build();
		ClassificationResult result = pipeline.classify(observation, new BaselineStore());
		assertEquals(Classification.LIKELY_EXISTING, result.classification());
		assertTrue(result.confidence() < 0.1);
	}

	@Test
	void standardPipelineBuildsAllSixClassifiers() {
		ClassifierPipeline pipeline = ClassifierPipeline.standard();
		ChunkObservation observation = TestObservations.builder().build();
		// Should run without throwing even with every classifier at cold-baseline defaults.
		ClassificationResult result = pipeline.classify(observation, new BaselineStore());
		assertEquals(Classification.UNKNOWN, result.classification());
	}
}

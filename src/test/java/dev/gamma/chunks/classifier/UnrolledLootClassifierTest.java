package dev.gamma.chunks.classifier;

import dev.gamma.chunks.model.ChunkObservation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnrolledLootClassifierTest {

	private final UnrolledLootClassifier classifier = new UnrolledLootClassifier();
	private final ClassifierContext context = new ClassifierContext(new BaselineStore(), "server", "minecraft:overworld");

	@Test
	void abstainsWithNoContainers() {
		ChunkObservation observation = TestObservations.builder().totalContainers(0).unrolledLootContainers(0).build();
		assertTrue(Double.isNaN(classifier.classify(observation, context)));
	}

	@Test
	void allUnrolledIsFullyNew() {
		ChunkObservation observation = TestObservations.builder().totalContainers(4).unrolledLootContainers(4).build();
		assertEquals(1.0, classifier.classify(observation, context), 1e-9);
	}

	@Test
	void noneUnrolledIsFullyExisting() {
		ChunkObservation observation = TestObservations.builder().totalContainers(4).unrolledLootContainers(0).build();
		assertEquals(-1.0, classifier.classify(observation, context), 1e-9);
	}

	@Test
	void halfUnrolledIsNeutral() {
		ChunkObservation observation = TestObservations.builder().totalContainers(4).unrolledLootContainers(2).build();
		assertEquals(0.0, classifier.classify(observation, context), 1e-9);
	}
}

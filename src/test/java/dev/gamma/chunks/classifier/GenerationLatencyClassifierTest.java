package dev.gamma.chunks.classifier;

import dev.gamma.chunks.model.ChunkObservation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationLatencyClassifierTest {

	@Test
	void abstainsWhenLatencyWasNeverTracked() {
		GenerationLatencyClassifier classifier = new GenerationLatencyClassifier();
		ClassifierContext context = new ClassifierContext(new BaselineStore(), "server", "minecraft:overworld");
		ChunkObservation observation = TestObservations.builder().requestLatencyMillis(-1).build();
		assertTrue(Double.isNaN(classifier.classify(observation, context)));
	}

	@Test
	void aSlowLoadAgainstAFastBaselineLeansNew() {
		GenerationLatencyClassifier classifier = new GenerationLatencyClassifier();
		ClassifierContext context = new ClassifierContext(new BaselineStore(), "server", "minecraft:overworld");

		for (long latency : new long[] {40, 45, 50, 42, 48, 44, 46, 43, 47, 45}) {
			classifier.classify(TestObservations.builder().requestLatencyMillis(latency).build(), context);
		}

		double signal = classifier.classify(TestObservations.builder().requestLatencyMillis(2000).build(), context);
		assertTrue(signal > 0, "a chunk far slower than baseline should lean toward 'new', was " + signal);
	}

	@Test
	void differentDimensionsCalibrateIndependently() {
		GenerationLatencyClassifier classifier = new GenerationLatencyClassifier();
		BaselineStore store = new BaselineStore();
		ClassifierContext overworld = new ClassifierContext(store, "server", "minecraft:overworld");
		ClassifierContext nether = new ClassifierContext(store, "server", "minecraft:the_nether");

		for (int i = 0; i < 10; i++) {
			classifier.classify(TestObservations.builder().requestLatencyMillis(50).build(), overworld);
		}
		// The nether baseline is still cold — it should abstain even though the overworld one is warmed up.
		double signal = classifier.classify(TestObservations.builder().requestLatencyMillis(2000).build(), nether);
		assertTrue(Double.isNaN(signal));
	}
}

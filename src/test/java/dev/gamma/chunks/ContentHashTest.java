package dev.gamma.chunks;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ContentHashTest {

	@Test
	void sameContentDifferentMapOrderHashesEqual() {
		Map<String, Integer> a = new LinkedHashMap<>();
		a.put("chest", 3);
		a.put("barrel", 1);

		Map<String, Integer> b = new LinkedHashMap<>();
		b.put("barrel", 1);
		b.put("chest", 3);

		long hashA = ContentHash.compute(a, Map.of(), 0.5, 1024);
		long hashB = ContentHash.compute(b, Map.of(), 0.5, 1024);
		assertEquals(hashA, hashB);
	}

	@Test
	void differentCountsHashDifferently() {
		long hashA = ContentHash.compute(Map.of("chest", 1), Map.of(), 0.5, 1024);
		long hashB = ContentHash.compute(Map.of("chest", 2), Map.of(), 0.5, 1024);
		assertNotEquals(hashA, hashB);
	}

	@Test
	void differentEntropyOrPacketSizeHashesDifferently() {
		long base = ContentHash.compute(Map.of(), Map.of(), 0.5, 1024);
		long differentEntropy = ContentHash.compute(Map.of(), Map.of(), 0.9, 1024);
		long differentSize = ContentHash.compute(Map.of(), Map.of(), 0.5, 2048);
		assertNotEquals(base, differentEntropy);
		assertNotEquals(base, differentSize);
	}
}

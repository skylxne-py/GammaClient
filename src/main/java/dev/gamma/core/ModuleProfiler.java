package dev.gamma.core;

import dev.gamma.core.event.GammaEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rolling per-module, per-event-type timing captured for free by every {@link Module#listen}
 * subscription. This is the closest thing to "profile the render path" (Phase 7) that's
 * possible without a live game client to attach a real sampling profiler to — see
 * the design notes for why actual FPS-cost numbers aren't fabricated here. Exposed in-game via
 * {@code .gamma profile}, so a developer with a running client can point at whichever module the
 * numbers blame.
 */
public final class ModuleProfiler {

	private static final Map<String, Stat> STATS = new ConcurrentHashMap<>();

	private ModuleProfiler() {
	}

	static void record(Module module, Class<? extends GammaEvent> eventType, long nanos) {
		STATS.computeIfAbsent(module.name() + "#" + eventType.getSimpleName(),
				key -> new Stat(module.name(), eventType.getSimpleName())).record(nanos);
	}

	public static List<Snapshot> snapshot() {
		return STATS.values().stream()
				.map(Stat::snapshot)
				.sorted((a, b) -> Double.compare(b.avgMicros(), a.avgMicros()))
				.toList();
	}

	public static void reset() {
		STATS.clear();
	}

	private static final class Stat {
		final String moduleName;
		final String eventName;
		final AtomicLong count = new AtomicLong();
		final AtomicLong totalNanos = new AtomicLong();
		final AtomicLong maxNanos = new AtomicLong();

		Stat(String moduleName, String eventName) {
			this.moduleName = moduleName;
			this.eventName = eventName;
		}

		void record(long nanos) {
			count.incrementAndGet();
			totalNanos.addAndGet(nanos);
			maxNanos.accumulateAndGet(nanos, Math::max);
		}

		Snapshot snapshot() {
			long samples = count.get();
			double avgMicros = samples == 0 ? 0.0 : (totalNanos.get() / (double) samples) / 1000.0;
			return new Snapshot(moduleName, eventName, samples, avgMicros, maxNanos.get() / 1000.0);
		}
	}

	public record Snapshot(String moduleName, String eventName, long samples, double avgMicros, double maxMicros) {
	}
}

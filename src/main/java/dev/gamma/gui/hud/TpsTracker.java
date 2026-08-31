package dev.gamma.gui.hud;

import dev.gamma.core.event.EventBus;
import dev.gamma.core.event.events.TickEvent;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Estimates server TPS purely from the observed spacing between our own client ticks — there is
 * no public tick-rate accessor on {@code ClientPacketListener} (see the design notes),
 * so this measures wall-clock time between {@link TickEvent} END events instead, the same
 * approach other client mods use.
 */
final class TpsTracker {

	private static final long WINDOW_MILLIS = 1000;
	private static final double MAX_TPS = 20.0;

	private final Deque<Long> tickTimestamps = new ArrayDeque<>();

	TpsTracker(EventBus eventBus) {
		eventBus.subscribe(TickEvent.class, event -> {
			if (event.phase() == TickEvent.Phase.END) {
				onTick();
			}
		});
	}

	private void onTick() {
		long now = System.currentTimeMillis();
		tickTimestamps.addLast(now);
		while (!tickTimestamps.isEmpty() && now - tickTimestamps.peekFirst() > WINDOW_MILLIS) {
			tickTimestamps.pollFirst();
		}
	}

	double currentTps() {
		if (tickTimestamps.size() < 2) {
			return MAX_TPS;
		}
		long span = tickTimestamps.peekLast() - tickTimestamps.peekFirst();
		if (span <= 0) {
			return MAX_TPS;
		}
		double measured = (tickTimestamps.size() - 1) * 1000.0 / span;
		return Math.min(MAX_TPS, measured);
	}
}

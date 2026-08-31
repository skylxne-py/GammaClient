package dev.gamma.core.event.events;

import dev.gamma.core.event.GammaEvent;

/**
 * Fired once per client tick, before and after vanilla's own tick logic runs
 * (mirrors Fabric's {@code START_CLIENT_TICK} / {@code END_CLIENT_TICK}).
 */
public record TickEvent(Phase phase) implements GammaEvent {

	public enum Phase {
		START,
		END
	}
}

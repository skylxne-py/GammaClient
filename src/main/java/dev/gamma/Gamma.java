package dev.gamma;

import dev.gamma.core.event.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Gamma {

	public static final String MOD_ID = "gamma";
	public static final Logger LOGGER = LoggerFactory.getLogger("Gamma");

	/**
	 * The one piece of core state reachable statically. Everything else (registry, config,
	 * keybinds, commands) is wired together as instance fields in {@link GammaClient} — this
	 * exists only because mixins (e.g. the packet-receive hook) can't receive constructor
	 * dependencies and need a fixed point to post events to.
	 */
	public static final EventBus EVENT_BUS = new EventBus();

	private Gamma() {
	}
}

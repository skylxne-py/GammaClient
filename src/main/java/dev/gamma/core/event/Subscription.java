package dev.gamma.core.event;

/**
 * Handle returned by {@link EventBus#subscribe}. Opaque outside the event package — pass it
 * back to {@link EventBus#unsubscribe} to remove the listener. Identity-based, not equals-based,
 * so two subscriptions for the same type and priority never collide.
 */
public final class Subscription {

	final Class<? extends GammaEvent> type;
	final EventBus.Handler<?> handler;

	Subscription(Class<? extends GammaEvent> type, EventBus.Handler<?> handler) {
		this.type = type;
		this.handler = handler;
	}
}

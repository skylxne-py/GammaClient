package dev.gamma.core.event;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central pub/sub for everything modules and core systems react to. Subscriber lists are
 * rebuilt (copy-on-write) only on {@link #subscribe}/{@link #unsubscribe}, which happen at
 * module enable/disable — rare. {@link #post} walks a plain array with no boxing, no iterator
 * allocation, and no lambda capture, so it stays allocation-free on the tick/render hot path.
 */
public final class EventBus {

	@SuppressWarnings("rawtypes")
	private static final Handler[] EMPTY = new Handler[0];

	private final Map<Class<? extends GammaEvent>, Handler<?>[]> handlers = new ConcurrentHashMap<>();

	public <T extends GammaEvent> Subscription subscribe(Class<T> type, int priority, EventListener<T> listener) {
		Handler<T> handler = new Handler<>(priority, listener);
		handlers.compute(type, (key, existing) -> {
			Handler<?>[] base = existing == null ? EMPTY : existing;
			Handler<?>[] updated = Arrays.copyOf(base, base.length + 1);
			updated[base.length] = handler;
			// Highest priority first; stable order among equal priorities isn't guaranteed.
			Arrays.sort(updated, Comparator.<Handler<?>>comparingInt(h -> h.priority).reversed());
			return updated;
		});
		return new Subscription(type, handler);
	}

	public <T extends GammaEvent> Subscription subscribe(Class<T> type, EventListener<T> listener) {
		return subscribe(type, Priority.NORMAL, listener);
	}

	public void unsubscribe(Subscription subscription) {
		handlers.computeIfPresent(subscription.type, (key, existing) -> {
			Handler<?>[] updated = Arrays.stream(existing)
					.filter(h -> h != subscription.handler)
					.toArray(Handler[]::new);
			return updated.length == 0 ? null : updated;
		});
	}

	@SuppressWarnings("unchecked")
	public <T extends GammaEvent> void post(T event) {
		Handler<?>[] subscribers = handlers.get(event.getClass());
		if (subscribers == null) {
			return;
		}
		boolean cancellable = event instanceof Cancellable;
		for (Handler<?> subscriber : subscribers) {
			((Handler<T>) subscriber).listener.handle(event);
			if (cancellable && ((Cancellable) event).isCancelled()) {
				break;
			}
		}
	}

	static final class Handler<T extends GammaEvent> {
		final int priority;
		final EventListener<T> listener;

		Handler(int priority, EventListener<T> listener) {
			this.priority = priority;
			this.listener = listener;
		}
	}
}

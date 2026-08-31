package dev.gamma.core.event;

/**
 * Implemented by events that a subscriber can veto. Once cancelled, {@link EventBus#post}
 * stops walking the remaining handlers for that dispatch.
 */
public interface Cancellable extends GammaEvent {

	boolean isCancelled();

	void setCancelled(boolean cancelled);
}

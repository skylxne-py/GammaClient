package dev.gamma.core.event;

/**
 * Marker for everything dispatched through {@link EventBus}. Carries no behavior itself —
 * concrete events are typically records so dispatch never allocates beyond the event instance.
 */
public interface GammaEvent {
}

package dev.gamma.core.event;

@FunctionalInterface
public interface EventListener<T extends GammaEvent> {

	void handle(T event);
}

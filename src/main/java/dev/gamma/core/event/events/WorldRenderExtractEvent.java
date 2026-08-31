package dev.gamma.core.event.events;

import dev.gamma.core.event.GammaEvent;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;

/**
 * Extraction-side half of the render split (see the project conventions "Extraction / render split").
 * Fired from Fabric's {@code LevelExtractionEvents.END_EXTRACTION} — this is where modules
 * should read world state and build the immutable draw payloads {@link WorldRenderEvent}
 * consumers will render from. Never issue draw calls in response to this event.
 */
public record WorldRenderExtractEvent(LevelExtractionContext context) implements GammaEvent {
}

package dev.gamma.core.event.events;

import dev.gamma.core.event.GammaEvent;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

/**
 * Render-side half of the render split. Fired from Fabric's
 * {@code LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN} — after terrain, before the vanilla
 * block outline and gizmos. The Phase 2 renderer flushes its batched draws here; nothing
 * before then should hold a reference to this event's context past its handler.
 */
public record WorldRenderEvent(LevelRenderContext context) implements GammaEvent {
}

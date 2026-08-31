package dev.gamma.core.event.events;

import dev.gamma.core.event.GammaEvent;
import net.minecraft.network.protocol.Packet;

/**
 * Fired for every packet the client receives, before vanilla handles it. Read-only by design —
 * intentionally NOT {@link dev.gamma.core.event.Cancellable}. This exists for observation only
 * (timing, content — e.g. the chunk classifiers), never for dropping or rewriting server-bound
 * state.
 */
public record PacketReceiveEvent(Packet<?> packet) implements GammaEvent {
}

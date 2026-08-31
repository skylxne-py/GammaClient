package dev.gamma.core.event.events;

import dev.gamma.core.event.GammaEvent;
import net.minecraft.client.multiplayer.ClientLevel;

/** Fired for the previously active {@link ClientLevel} right before it stops being current. */
public record WorldUnloadEvent(ClientLevel level) implements GammaEvent {
}

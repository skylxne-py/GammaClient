package dev.gamma.core.event.events;

import dev.gamma.core.event.GammaEvent;
import net.minecraft.client.multiplayer.ClientLevel;

/** Fired once a new {@link ClientLevel} becomes the active one (join, respawn, dimension change). */
public record WorldLoadEvent(ClientLevel level) implements GammaEvent {
}

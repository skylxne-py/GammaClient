package dev.gamma.core.event.events;

import dev.gamma.core.event.GammaEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/** Fired when the client finishes loading a chunk it received from the server. */
public record ChunkReceiveEvent(ClientLevel level, LevelChunk chunk) implements GammaEvent {
}

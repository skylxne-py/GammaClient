package dev.gamma.core.event;

import dev.gamma.core.event.events.ChunkReceiveEvent;
import dev.gamma.core.event.events.CommandSendEvent;
import dev.gamma.core.event.events.TickEvent;
import dev.gamma.core.event.events.WorldLoadEvent;
import dev.gamma.core.event.events.WorldRenderEvent;
import dev.gamma.core.event.events.WorldRenderExtractEvent;
import dev.gamma.core.event.events.WorldUnloadEvent;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Adapts Fabric's tick/chunk/level/render events onto Gamma's own {@link EventBus} — modules
 * and other core systems subscribe to the bus, not to Fabric directly, so a future Fabric API
 * change (or the renderer wanting a different render stage) touches one file. Commands
 * and the client-stopping shutdown hook still talk to Fabric API directly; this covers only
 * the six event categories the EventBus exposes (tick, world render extract/render, packet
 * receive, key input, world load/unload, chunk receive).
 */
public final class FabricEventBridge {

	private final EventBus eventBus;
	private ClientLevel currentLevel;

	public FabricEventBridge(EventBus eventBus) {
		this.eventBus = eventBus;
	}

	public void install() {
		ClientTickEvents.START_CLIENT_TICK.register(client -> eventBus.post(new TickEvent(TickEvent.Phase.START)));
		ClientTickEvents.END_CLIENT_TICK.register(client -> eventBus.post(new TickEvent(TickEvent.Phase.END)));

		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> eventBus.post(new ChunkReceiveEvent(level, chunk)));

		// COMMAND, not ALLOW_COMMAND: observation only, so a handler can't suppress or rewrite
		// what the player actually sent.
		ClientSendMessageEvents.COMMAND.register(command -> eventBus.post(new CommandSendEvent(command)));

		// AFTER_CLIENT_LEVEL_CHANGE fires with the new level (null when leaving to no world at
		// all); we track the previous level ourselves to split it into separate load/unload events.
		ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, newLevel) -> {
			if (currentLevel != null) {
				eventBus.post(new WorldUnloadEvent(currentLevel));
			}
			currentLevel = newLevel;
			if (newLevel != null) {
				eventBus.post(new WorldLoadEvent(newLevel));
			}
		});

		// Deliberately AFTER_BLOCK_OUTLINE_EXTRACTION and not END_EXTRACTION, despite the latter
		// being the obvious-looking "extraction is done" hook. END_EXTRACTION injects at RETURN of
		// LevelExtractor.extract, but that method's *last* real statement is extractGizmos(),
		// which drains its own main-thread SimpleGizmoCollector into LevelRenderer for this
		// frame. Anything submitted from an END_EXTRACTION handler therefore lands in the
		// collector just after it was drained, and is only picked up by the *next* frame's drain
		// -- i.e. every Renderer3D call was being displayed one frame late. World-anchored shapes
		// (ESP boxes, chunk overlays) hide that completely: a one-frame-old world-space box is
		// the same box. A tracer origin is derived from the camera, so it showed up as the origin
		// lagging the crosshair by exactly one frame of camera motion -- drift while turning,
		// flying or walking, snapping back to centre the moment the camera stopped, which is the
		// behaviour that was previously misattributed to view bobbing. AFTER_BLOCK_OUTLINE_-
		// EXTRACTION injects at RETURN of extractBlockOutline, called unconditionally partway
		// through extract() and well before extractGizmos(), so submissions make this frame's
		// drain. The camera is fully updated by then (GameRenderer.update -> Camera.update runs
		// before GameRenderer.extract), so nothing else about the payloads changes.
		LevelExtractionEvents.AFTER_BLOCK_OUTLINE_EXTRACTION.register((context, hitResult) -> eventBus.post(new WorldRenderExtractEvent(context)));
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(context -> eventBus.post(new WorldRenderEvent(context)));
	}
}

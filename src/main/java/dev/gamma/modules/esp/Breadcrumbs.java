package dev.gamma.modules.esp;

import dev.gamma.Gamma;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.ColorSetting;
import dev.gamma.config.setting.DoubleSetting;
import dev.gamma.config.setting.EnumSetting;
import dev.gamma.config.setting.IntSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.core.event.Subscription;
import dev.gamma.core.event.events.TickEvent;
import dev.gamma.core.event.events.WorldRenderExtractEvent;
import dev.gamma.render.RenderPass;
import dev.gamma.render.Renderer3D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** A time-limited trail behind other players, and optionally yourself. */
public final class Breadcrumbs extends Module {

	private final BoolSetting trackSelf = register(new BoolSetting("TrackSelf", "Also trail behind you.", false));
	private final BoolSetting trackOthers = register(new BoolSetting("TrackOthers", "Trail behind other players.", true));
	private final IntSetting trailSeconds = register(new IntSetting("TrailSeconds", "How long a trail point stays visible, in seconds.", 30, 2, 600));
	private final DoubleSetting minSegmentDistance = register(new DoubleSetting("MinSegmentDistance", "Minimum movement, in blocks, before a new trail point is recorded.", 0.5, 0.05, 5.0));
	private final ColorSetting selfColor = register(new ColorSetting("SelfColor", "Trail color for yourself.", 0xFF33FF88));
	private final ColorSetting othersColor = register(new ColorSetting("OthersColor", "Trail color for other players.", 0xFFFFAA33));
	private final EnumSetting<RenderPass> pass = register(new EnumSetting<>("Pass", "Depth-test behavior.", RenderPass.class, RenderPass.THROUGH_WALLS));

	private final Map<UUID, Deque<Point>> trails = new HashMap<>();

	private Subscription tickSubscription;
	private Subscription extractSubscription;

	public Breadcrumbs() {
		super("Breadcrumbs", "Draws a time-limited trail behind players.", Category.ESP);
	}

	@Override
	protected void onEnable() {
		trails.clear();
		tickSubscription = listen(TickEvent.class, this::onTick);
		extractSubscription = listen(WorldRenderExtractEvent.class, this::onExtract);
	}

	@Override
	protected void onDisable() {
		Gamma.EVENT_BUS.unsubscribe(tickSubscription);
		Gamma.EVENT_BUS.unsubscribe(extractSubscription);
		trails.clear();
	}

	private void onTick(TickEvent event) {
		if (event.phase() != TickEvent.Phase.END) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) {
			return;
		}
		long now = System.currentTimeMillis();
		long maxAgeMillis = trailSeconds.get() * 1000L;
		double minDistance = minSegmentDistance.get();

		for (AbstractClientPlayer player : client.level.players()) {
			boolean self = player == client.player;
			if (self && !trackSelf.get() || !self && !trackOthers.get()) {
				continue;
			}
			Deque<Point> trail = trails.computeIfAbsent(player.getUUID(), id -> new ArrayDeque<>());
			Point last = trail.peekLast();
			if (last == null || last.pos.distanceToSqr(player.position()) >= minDistance * minDistance) {
				trail.addLast(new Point(player.position(), now));
			}
			pruneOld(trail, now, maxAgeMillis);
		}
		trails.keySet().removeIf(id -> trails.get(id).isEmpty());
	}

	private static void pruneOld(Deque<Point> trail, long now, long maxAgeMillis) {
		Iterator<Point> iterator = trail.iterator();
		while (iterator.hasNext()) {
			Point point = iterator.next();
			if (now - point.timestampMillis > maxAgeMillis) {
				iterator.remove();
			} else {
				break;
			}
		}
	}

	private void onExtract(WorldRenderExtractEvent event) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}
		for (Map.Entry<UUID, Deque<Point>> entry : trails.entrySet()) {
			boolean self = entry.getKey().equals(client.player.getUUID());
			int color = self ? selfColor.get() : othersColor.get();
			Point previous = null;
			for (Point point : entry.getValue()) {
				if (previous != null) {
					Renderer3D.line(previous.pos, point.pos, color, pass.get());
				}
				previous = point;
			}
		}
	}

	private record Point(Vec3 pos, long timestampMillis) {
	}
}

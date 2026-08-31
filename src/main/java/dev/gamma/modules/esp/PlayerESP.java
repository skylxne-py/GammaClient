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
import dev.gamma.core.event.events.WorldRenderExtractEvent;
import dev.gamma.render.Culling;
import dev.gamma.render.EspMode;
import dev.gamma.render.EspShapeRenderer;
import dev.gamma.render.RenderPass;
import dev.gamma.render.Renderer3D;
import dev.gamma.render.ShapeBuilder;
import dev.gamma.util.ColorUtil;
import dev.gamma.util.ToastNotifier;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Like {@link EntityESP} but for other players specifically, with a richer nametag: name, health
 * bar, distance, held item, armor + durability, ping. "Always-legible" grows the world-space
 * text scale with distance so it stays roughly the same apparent size regardless of range —
 * there's no true screen-space world-text primitive (see the project's RenderType note).
 */
public final class PlayerESP extends Module {

	private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	private static final double NAMETAG_LINE_HEIGHT = 0.28;
	private static final double NAMETAG_BASE_OFFSET = 0.35;

	private final ColorSetting color = register(new ColorSetting("Color", "Box color.", 0xFF55CCFF));
	private final EnumSetting<EspMode> mode = register(new EnumSetting<>("Mode", "Box / tracer / both.", EspMode.class, EspMode.BOX));
	private final EnumSetting<RenderPass> pass = register(new EnumSetting<>("Pass", "Depth-test behavior.", RenderPass.class, RenderPass.BOTH));
	private final DoubleSetting maxDistance = register(new DoubleSetting("MaxDistance", "Max render distance, in blocks.", 96.0, 4.0, 1024.0));
	private final IntSetting maxHeight = register(new IntSetting("MaxHeight", "Only show players at or below this Y, to cut surface noise when hunting underground. 320 = no limit.", Culling.HEIGHT_LIMIT_OFF, -64, Culling.HEIGHT_LIMIT_OFF));
	private final BoolSetting distanceFade = register(new BoolSetting("DistanceFade", "Fade alpha with distance.", true));
	private final BoolSetting toast = register(new BoolSetting("Toast", "Show a notification above the hotbar when a player comes into range, so you notice one arriving while looking the other way.", true));

	private final BoolSetting nametags = register(new BoolSetting("Nametags", "Draw the info nametag above each player.", true));
	private final BoolSetting showHealth = register(new BoolSetting("ShowHealth", "Include health in the nametag.", true, nametags::get));
	private final BoolSetting showDistance = register(new BoolSetting("ShowDistance", "Include distance in the nametag.", true, nametags::get));
	private final BoolSetting showHeldItem = register(new BoolSetting("ShowHeldItem", "Include the held item in the nametag.", true, nametags::get));
	private final BoolSetting showArmor = register(new BoolSetting("ShowArmor", "Include armor + durability in the nametag.", true, nametags::get));
	private final BoolSetting showPing = register(new BoolSetting("ShowPing", "Include ping in the nametag.", false, nametags::get));
	private final BoolSetting alwaysLegible = register(new BoolSetting("AlwaysLegible", "Scale the nametag up with distance so it stays readable.", true, nametags::get));
	private final BoolSetting seeThrough = register(new BoolSetting("SeeThrough", "Always draw the nametag through walls, even if the box isn't.", true, nametags::get));

	private Subscription subscription;

	/**
	 * Who was in range last frame, so an arrival can be told from someone merely still standing
	 * there. Names rather than UUIDs because that is what the toast has to print anyway, and a name
	 * collision within one server's player list is not a thing that happens.
	 *
	 * <p>Rebuilt from the visible set each frame rather than maintained by add/remove events: this
	 * has to track "passes the module's own distance and height filters", which no event knows
	 * about, and which changes as you move rather than as anyone joins or leaves.
	 */
	private final Set<String> inRange = new HashSet<>();
	private final ToastNotifier toasts = new ToastNotifier(2_000);

	public PlayerESP() {
		super("PlayerESP", "Highlights other players through walls, with a detailed nametag.", Category.ESP);
	}

	@Override
	protected void onEnable() {
		inRange.clear();
		subscription = listen(WorldRenderExtractEvent.class, this::onExtract);
	}

	@Override
	protected void onDisable() {
		Gamma.EVENT_BUS.unsubscribe(subscription);
		inRange.clear();
	}

	private void onExtract(WorldRenderExtractEvent event) {
		ClientLevel level = event.context().level();
		Camera camera = event.context().camera();
		float partialTick = event.context().deltaTracker().getGameTimeDeltaPartialTick(false);
		double max = maxDistance.get();
		int base = color.get();
		// Null when the toast is off, so the common case allocates nothing per frame — this runs in
		// the extraction path, where a set built and thrown away every frame is a real cost for a
		// feature that isn't switched on.
		Set<String> nearby = toast.get() ? new HashSet<>() : null;

		for (AbstractClientPlayer player : level.players()) {
			if (player == camera.entity()) {
				continue;
			}
			AABB box = ShapeBuilder.entityBox(player, partialTick);
			if (!Culling.belowHeight(box.minY, maxHeight.get())) {
				continue;
			}
			if (!Culling.withinDistance(camera.position(), box.getCenter(), max)) {
				continue;
			}
			if (nearby != null) {
				// Before the frustum test, not after: the whole value of being told someone is nearby
				// is that you are looking somewhere else when they turn up.
				nearby.add(player.getGameProfile().name());
			}
			// Frustum-culled for the box/nametag only -- a tracer's whole point is showing
			// something that isn't in view, so it always draws within distance regardless.
			boolean inFrustum = Culling.isVisible(camera.getCullFrustum(), box);

			double distance = Math.sqrt(camera.position().distanceToSqr(box.getCenter()));
			int drawColor = distanceFade.get() ? ColorUtil.scaleAlpha(base, 1.0 - Math.min(1.0, distance / max) * 0.7) : base;

			EspShapeRenderer.draw(box, drawColor, pass.get(), mode.get(), camera, inFrustum);
			if (nametags.get() && inFrustum) {
				drawNametag(player, box, distance, drawColor);
			}
		}

		if (nearby == null) {
			// Dropped rather than kept: coming back from the toast being off should not announce
			// everyone who wandered in while it was off, all at once.
			inRange.clear();
			return;
		}
		announceArrivals(nearby);
	}

	/** Toasts anyone in {@code nearby} who wasn't there last frame, then adopts it as the new baseline. */
	private void announceArrivals(Set<String> nearby) {
		List<String> arrived = new ArrayList<>();
		for (String name : nearby) {
			if (!inRange.contains(name)) {
				arrived.add(name);
			}
		}
		inRange.clear();
		inRange.addAll(nearby);
		if (arrived.isEmpty()) {
			return;
		}
		// A group arriving together is one event, not four toasts the notifier would collapse into
		// whichever name happened to be last.
		String message = arrived.size() == 1
				? arrived.getFirst()
				: arrived.getFirst() + " and " + (arrived.size() - 1) + " more";
		toasts.show("Player in range", message);
	}

	private void drawNametag(Player player, AABB box, double distance, int color) {
		List<String> lines = new ArrayList<>();
		lines.add(player.getGameProfile().name());
		if (showHealth.get()) {
			lines.add(String.format("%.1f / %.1f hp", player.getHealth(), player.getMaxHealth()));
		}
		if (showDistance.get()) {
			lines.add(String.format("%.1fm", distance));
		}
		if (showHeldItem.get() && !player.getMainHandItem().isEmpty()) {
			lines.add(player.getMainHandItem().getHoverName().getString());
		}
		if (showArmor.get()) {
			String armor = armorSummary(player);
			if (!armor.isEmpty()) {
				lines.add(armor);
			}
		}
		if (showPing.get()) {
			int ping = latencyOf(player);
			if (ping >= 0) {
				lines.add(ping + " ms");
			}
		}

		float scale = alwaysLegible.get() ? (float) Math.max(1.0, distance / 12.0) : 1.0f;
		RenderPass textPass = seeThrough.get() ? RenderPass.THROUGH_WALLS : pass.get();

		Vec3 top = new Vec3(box.getCenter().x, box.maxY + NAMETAG_BASE_OFFSET, box.getCenter().z);
		for (int i = 0; i < lines.size(); i++) {
			Vec3 pos = top.add(0, (lines.size() - 1 - i) * NAMETAG_LINE_HEIGHT * scale, 0);
			Renderer3D.text3d(pos, lines.get(i), color, scale, textPass);
		}
	}

	private static String armorSummary(Player player) {
		StringBuilder builder = new StringBuilder();
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			ItemStack stack = player.getItemBySlot(slot);
			if (stack.isEmpty()) {
				continue;
			}
			if (!builder.isEmpty()) {
				builder.append(' ');
			}
			builder.append(stack.getHoverName().getString());
			if (stack.isDamageableItem()) {
				int durability = stack.getMaxDamage() - stack.getDamageValue();
				builder.append(" (").append(Math.round(100.0 * durability / stack.getMaxDamage())).append("%)");
			}
		}
		return builder.toString();
	}

	private static int latencyOf(Player player) {
		var connection = Minecraft.getInstance().getConnection();
		if (connection == null) {
			return -1;
		}
		PlayerInfo info = connection.getPlayerInfo(player.getUUID());
		return info == null ? -1 : info.getLatency();
	}
}

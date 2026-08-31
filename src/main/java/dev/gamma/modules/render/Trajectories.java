package dev.gamma.modules.render;

import dev.gamma.Gamma;
import dev.gamma.config.setting.ColorSetting;
import dev.gamma.config.setting.IntSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.core.event.Subscription;
import dev.gamma.core.event.events.WorldRenderExtractEvent;
import dev.gamma.render.RenderPass;
import dev.gamma.render.Renderer3D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Predicted arc for a drawn bow, a charged trident, or a held throwable — a straightforward
 * gravity + drag simulation using vanilla's own projectile constants, drawn with
 * {@link Renderer3D}. Not pixel-perfect against the server's actual RNG-free trajectory (no
 * mixin reaches into that), but physically the same model vanilla uses.
 */
public final class Trajectories extends Module {

	private static final double GRAVITY = 0.05;
	private static final double DRAG = 0.99;
	private static final int MAX_STEPS = 200;

	private final ColorSetting color = register(new ColorSetting("Color", "Trajectory line color.", 0xFFFFDD55));
	private final IntSetting steps = register(new IntSetting("Steps", "How many simulated ticks to draw ahead.", 60, 5, MAX_STEPS));

	private Subscription extractSubscription;

	public Trajectories() {
		super("Trajectories", "Draws a predicted arc for bows, throwables, and tridents.", Category.RENDER);
	}

	@Override
	protected void onEnable() {
		extractSubscription = listen(WorldRenderExtractEvent.class, this::onExtract);
	}

	@Override
	protected void onDisable() {
		Gamma.EVENT_BUS.unsubscribe(extractSubscription);
	}

	private void onExtract(WorldRenderExtractEvent event) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		Double speed = launchSpeed(player);
		if (speed == null) {
			return;
		}

		Vec3 pos = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		Vec3 velocity = look.scale(speed);
		Vec3 previous = pos;

		for (int i = 0; i < Math.min(steps.get(), MAX_STEPS); i++) {
			pos = pos.add(velocity);
			velocity = velocity.subtract(0, GRAVITY, 0).scale(DRAG);

			BlockHitResult hit = player.level().clip(new ClipContext(previous, pos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
			if (hit.getType() != HitResult.Type.MISS) {
				Renderer3D.line(previous, hit.getLocation(), color.get(), RenderPass.THROUGH_WALLS);
				return;
			}
			Renderer3D.line(previous, pos, color.get(), RenderPass.THROUGH_WALLS);
			previous = pos;
		}
	}

	/** {@code null} means "not currently doing anything projectile-shaped". */
	private static Double launchSpeed(LocalPlayer player) {
		ItemStack stack = player.getMainHandItem();
		if (player.isUsingItem()) {
			if (stack.getItem() instanceof BowItem) {
				float charge = player.getTicksUsingItem() / 20.0f;
				float power = (charge * charge + charge * 2) / 3.0f;
				return Math.min(1.0f, power) * 3.0;
			}
			if (stack.getItem() instanceof TridentItem) {
				return 2.5;
			}
		} else if (stack.getItem() instanceof ProjectileItem) {
			return 1.5;
		}
		return null;
	}
}

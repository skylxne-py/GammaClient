package dev.gamma.modules.render;

import dev.gamma.Gamma;
import dev.gamma.config.GammaSettings;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.DoubleSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.core.event.Subscription;
import dev.gamma.core.event.events.TickEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Detached camera: the world stays loaded and simulating, the real player stays exactly where
 * it was, but the render camera roams free under WASD/space/shift. The only way to move the
 * render camera independently of the tracked entity is {@link dev.gamma.mixin.render.CameraMixin}
 * overriding {@code Camera.update} — there's no Fabric API seam for that.
 *
 * <p>Position integrates in {@link #advance()}, called once per frame from that same mixin
 * injection point, using real elapsed time rather than fixed 1/20s tick steps -- tick-driven
 * movement only advances 20 times a second no matter the framerate, so the camera held the same
 * position across multiple rendered frames between ticks and visibly stepped. Real-time
 * integration keeps it frame-rate independent, the same way vanilla flight feels smooth.
 *
 * <p>Interaction (breaking blocks, eating, ...) already targets from the real player's own eye
 * position and look direction, not the camera's -- {@code Minecraft.pick()} raycasts from
 * {@code getCameraEntity()} (the tracked entity, untouched by this module) via its own
 * {@code getEyePosition()}/rotation, never through the overridden {@code Camera}. No change
 * needed there. {@code ShowBody} instead addresses seeing your own avatar: forcing
 * {@code Options.cameraType} to third-person makes vanilla's own (already fully correct)
 * third-person player rendering kick in, and {@link dev.gamma.mixin.render.CameraMixin} still
 * fully overrides the resulting camera position/rotation afterward regardless of that setting,
 * so the free-flying behavior itself is unaffected -- this only changes whether vanilla draws
 * the player model it's flying away from.
 *
 * <p>The real player is kept still by {@link dev.gamma.mixin.render.KeyboardInputMixin}, which
 * blanks the per-tick input state outright — see there for why cancelling {@code applyInput} alone
 * left crouching, sprinting and vehicle steering still live. This module reads the
 * {@code KeyMapping}s directly in {@link #advance()} rather than going through
 * {@code ClientInput}, so blanking that state does not disarm the camera itself.
 *
 * <p><b>This module does not load chunks, on purpose.</b> Two previous attempts at it were
 * removed — {@code SpoofPosition} (2026-08-03) and {@code FreecamChunkLoader}/
 * {@code FreecamViewRadius} (2026-08-04); see the design notes for both. The second did
 * real damage: widening the client's chunk ring buffer means resizing it again on the way out, and
 * that resize is not something vanilla ever reissues, so it left the ring pinned at whatever the
 * render distance happened to be when Freecam was switched off — chunks arriving outside it were
 * then discarded on receipt for the rest of the session. A detached camera showing only terrain
 * the client already holds is a mild limitation; silently breaking chunk loading for everything
 * else is not. The camera stays a camera.
 */
public final class Freecam extends Module {

	public static volatile Freecam instance;

	private final DoubleSetting speed = register(new DoubleSetting("Speed", "Blocks per second.", 10.0, 1.0, 100.0));
	private final BoolSetting showBody = register(new BoolSetting("ShowBody", "Render your own avatar while freecamming, like third person.", true));
	private final BoolSetting disableOnDamage = register(new BoolSetting("DisableOnDamage", "Snap back the moment you take damage. The real player is standing still and defenceless while you fly, so the first hit is the last moment you can react to it.", true));

	private Vec3 position;
	private float yaw;
	private float pitch;
	private long lastAdvanceNanos;
	private CameraType previousCameraType;
	private Subscription tickSubscription;
	private float lastHealth;

	public Freecam() {
		super("Freecam", "Detaches the camera from the player; you stay put and the world keeps simulating.", Category.RENDER);
		instance = this;
	}

	@Override
	protected void onEnable() {
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			position = client.player.getEyePosition();
			yaw = client.player.getYRot();
			pitch = client.player.getXRot();
		} else {
			position = Vec3.ZERO;
			yaw = 0;
			pitch = 0;
		}
		lastAdvanceNanos = System.nanoTime();
		// Baselined at enable, not at zero: enabling one tick after being hit would otherwise read as
		// a fresh drop and switch straight back off.
		lastHealth = client.player != null ? client.player.getHealth() : 0f;
		if (showBody.get()) {
			previousCameraType = client.options.getCameraType();
			client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		}
		tickSubscription = listen(TickEvent.class, this::onTick);
	}

	@Override
	protected void onDisable() {
		Gamma.EVENT_BUS.unsubscribe(tickSubscription);
		if (previousCameraType != null) {
			Minecraft.getInstance().options.setCameraType(previousCameraType);
			previousCameraType = null;
		}
	}

	/**
	 * Health is the signal rather than a hook on the damage path: for the local player, damage
	 * arrives as a {@code ClientboundSetHealthPacket} the server sends after the fact, so the health
	 * value is the thing the client actually learns. It also covers sources with no attacker at all —
	 * drowning, fall damage, poison, a creeper you never saw.
	 */
	private void onTick(TickEvent event) {
		if (event.phase() != TickEvent.Phase.END) {
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		float health = player.getHealth();
		boolean hurt = health < lastHealth;
		lastHealth = health;
		if (hurt && disableOnDamage.get()) {
			setEnabled(false);
			if (GammaSettings.chatMessagesEnabled()) {
				player.sendSystemMessage(Component.literal("[Gamma] ").withStyle(ChatFormatting.DARK_GRAY)
						.append(Component.literal("Freecam off — you took damage").withStyle(ChatFormatting.RED)));
			}
		}
	}

	public Vec3 position() {
		return position;
	}

	public float yaw() {
		return yaw;
	}

	public float pitch() {
		return pitch;
	}

	/** Fed the same look deltas {@link dev.gamma.mixin.render.CameraMixin} would otherwise ignore. */
	public void look(double dYaw, double dPitch) {
		yaw = (float) (yaw + dYaw);
		pitch = (float) Math.max(-90.0, Math.min(90.0, pitch + dPitch));
	}

	/** Called once per rendered frame from {@link dev.gamma.mixin.render.CameraMixin}, before the moved position is read. */
	public void advance() {
		long now = System.nanoTime();
		double deltaSeconds = Math.min(0.25, (now - lastAdvanceNanos) / 1.0e9);
		lastAdvanceNanos = now;

		Minecraft client = Minecraft.getInstance();
		Vec3 forward = Vec3.directionFromRotation(0, yaw);
		// Verified against LocalPlayer.applyInput()/KeyboardInput: xxa (strafe) is +1 for A
		// (left) and -1 for D (right) -- the opposite of the intuitive "D is positive" guess a
		// previous pass made here, which had this vector exactly backwards as a result.
		Vec3 right = new Vec3(-forward.z, 0, forward.x);
		Vec3 move = Vec3.ZERO;

		Options options = client.options;
		if (options.keyUp.isDown()) {
			move = move.add(forward);
		}
		if (options.keyDown.isDown()) {
			move = move.subtract(forward);
		}
		if (options.keyRight.isDown()) {
			move = move.add(right);
		}
		if (options.keyLeft.isDown()) {
			move = move.subtract(right);
		}
		if (options.keyJump.isDown()) {
			move = move.add(0, 1, 0);
		}
		if (options.keyShift.isDown()) {
			move = move.add(0, -1, 0);
		}
		if (move.lengthSqr() > 0) {
			position = position.add(move.normalize().scale(speed.get() * deltaSeconds));
		}
	}
}

package dev.gamma.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.gamma.modules.render.NoRender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * Reproduces the extra view-space transform vanilla applies to the whole level *after* the
 * camera, so camera-anchored geometry can undo it.
 *
 * <p>{@code GameRenderer.renderLevel} does not render the world with the camera's own matrices
 * alone. It copies {@code CameraRenderState.projectionMatrix}, builds a fresh {@code PoseStack},
 * runs {@code bobHurt} and (when the option is on) {@code bobView} into it, and multiplies that
 * pose into the projection copy. The final transform for every world vertex is therefore
 * {@code Proj · B · ViewRot · (world − cameraPos)}, where {@code B} is that bob pose — a
 * translation plus rotations about the eye that is reflected nowhere in the {@code Camera}
 * object. World-anchored shapes don't care: they move with the world, exactly as intended. A
 * tracer origin does, because it is supposed to stay welded to the crosshair, and the crosshair
 * is HUD-space and unbobbed.
 *
 * <p>Rather than suppress bobbing (a user-visible vanilla behaviour, and no help for
 * {@code bobHurt}, which has no option at all), {@link ShapeBuilder#tracerOrigin} inverts this
 * matrix to pick the world point that lands dead centre once vanilla has bobbed it.
 *
 * <p>Every input is read from the already-extracted {@code CameraRenderState}/
 * {@code OptionsRenderState}, which {@code GameRenderer.extract} fills in before
 * {@code LevelExtractor.extract} runs, so this is the same data {@code renderLevel} will use
 * later in the frame — not a tick-old approximation. Both methods are private, hence the
 * replica; they are mirrored statement for statement against 26.2's own bytecode.
 *
 * <p>Not modelled: the nausea/portal spin, which {@code renderLevel} multiplies into the same
 * matrix. It is a deliberate whole-screen distortion, so a tracer swimming with it is correct.
 */
public final class ViewBobTransform {

	private ViewBobTransform() {
	}

	/**
	 * The current frame's bob transform, or {@code null} when it is the identity — the common
	 * case (standing still, undamaged), which callers can skip entirely. Freshly built per call
	 * and owned by the caller, so it is safe to invert or otherwise mutate in place.
	 */
	public static Matrix4f current() {
		Minecraft client = Minecraft.getInstance();
		GameRenderer gameRenderer = client.gameRenderer;
		if (gameRenderer == null) {
			return null;
		}
		GameRenderState gameState = gameRenderer.gameRenderState();
		CameraRenderState camera = gameState.levelRenderState.cameraRenderState;
		OptionsRenderState options = gameState.optionsRenderState;
		if (camera == null || camera.entityRenderState == null || options == null) {
			return null;
		}
		CameraEntityRenderState entity = camera.entityRenderState;

		// NoRender's toggles cancel the vanilla methods outright at the head (GameRendererMixin),
		// so the same gating has to apply here or we would compensate for a transform that never
		// gets applied.
		NoRender noRender = NoRender.instance;
		boolean hurtCam = noRender == null || noRender.hurtCamEnabled();
		boolean viewBob = options.bobView && (noRender == null || noRender.viewBobbingEnabled());

		// Both vanilla methods reduce to the identity for these inputs (bobHurt's tilt is scaled
		// by sin(0), bobView's translate and rotations all by bob), so screening them out here is
		// exact -- and keeps the overwhelmingly common case (standing still, undamaged, or
		// bobbing simply switched off) from allocating anything at all.
		boolean hurting = hurtCam && entity.isLiving && (entity.isDeadOrDying || entity.hurtTime > 0.0f);
		boolean bobbing = viewBob && entity.isPlayer && entity.bob != 0.0f;
		if (!hurting && !bobbing) {
			return null;
		}

		PoseStack pose = new PoseStack();
		if (hurting) {
			bobHurt(pose, entity, options.damageTiltStrength);
		}
		if (bobbing) {
			bobView(pose, entity);
		}
		return pose.last().pose();
	}

	/** Mirrors {@code GameRenderer.bobHurt}. */
	private static void bobHurt(PoseStack pose, CameraEntityRenderState entity, double damageTiltStrength) {
		if (entity.isDeadOrDying) {
			float deathTime = Math.min(entity.deathTime, 20.0f);
			pose.mulPose(Axis.ZP.rotationDegrees(40.0f - 8000.0f / (deathTime + 200.0f)));
		}
		float hurtTime = entity.hurtTime;
		if (hurtTime < 0.0f) {
			return;
		}
		hurtTime /= entity.hurtDuration;
		hurtTime = Mth.sin(hurtTime * hurtTime * hurtTime * hurtTime * (float) Math.PI);
		float hurtDir = entity.hurtDir;
		pose.mulPose(Axis.YP.rotationDegrees(-hurtDir));
		pose.mulPose(Axis.ZP.rotationDegrees((float) (-hurtTime * 14.0 * damageTiltStrength)));
		pose.mulPose(Axis.YP.rotationDegrees(hurtDir));
	}

	/** Mirrors {@code GameRenderer.bobView}. */
	private static void bobView(PoseStack pose, CameraEntityRenderState entity) {
		float bob = entity.bob;
		float phase = entity.backwardsInterpolatedWalkDistance * (float) Math.PI;
		pose.translate(Mth.sin(phase) * bob * 0.5f, -Math.abs(Mth.cos(phase) * bob), 0.0f);
		pose.mulPose(Axis.ZP.rotationDegrees(Mth.sin(phase) * bob * 3.0f));
		pose.mulPose(Axis.XP.rotationDegrees(Math.abs(Mth.cos(phase - 0.2f) * bob) * 5.0f));
	}
}

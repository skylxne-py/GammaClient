package dev.gamma.mixin.render;

import dev.gamma.modules.render.Freecam;
import dev.gamma.modules.render.Freelook;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code Camera.update} is the one place per frame that recomputes position and rotation from
 * the tracked entity — there's no Fabric API seam to override it, so {@code Freecam} (full
 * position + rotation override) and {@code Freelook} (rotation-only offset, camera stays with
 * the entity) both hook here, at the tail, after vanilla's own tracking has already run.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

	@Shadow
	protected abstract void setPosition(net.minecraft.world.phys.Vec3 pos);

	// The real parameter order (verified against the compiled method body) is (yRot, xRot) --
	// i.e. (yaw, pitch) -- not (xRot, yRot)/(pitch, yaw) as the field names alone would suggest.
	// Every call below was passing (pitch, yaw), which put pitch in the camera's yaw slot (hence
	// clamped-like left/right turning) and unclamped yaw in the camera's pitch slot (hence free
	// spinning when moving the mouse horizontally).
	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Inject(method = "update", at = @At("TAIL"))
	private void gamma$overrideCamera(DeltaTracker deltaTracker, CallbackInfo ci) {
		Freecam freecam = Freecam.instance;
		if (freecam != null && freecam.isEnabled()) {
			freecam.advance();
			setPosition(freecam.position());
			setRotation(freecam.yaw(), freecam.pitch());
			return;
		}

		Freelook freelook = Freelook.instance;
		if (freelook != null && freelook.isEnabled()) {
			Camera self = (Camera) (Object) this;
			Entity entity = self.entity();
			if (entity != null) {
				float newYaw = entity.getYRot() + freelook.yawOffset();
				float newPitch = entity.getXRot() + freelook.pitchOffset();
				if (self.isDetached()) {
					// Third person: vanilla already positioned the camera behind the entity,
					// using its own (unmodified) rotation and its own wall-collision-aware
					// distance clamp, before this injection ever runs. Only overriding rotation
					// left that position fixed in space, so Freelook just rotated in place
					// around a point rather than orbiting the player -- reusing the distance
					// vanilla already computed, but re-aiming it along the offset look
					// direction, keeps the player as the focus point the way third-person
					// freelook is expected to work.
					Vec3 eyePos = entity.getEyePosition(self.getCameraEntityPartialTicks(deltaTracker));
					double distance = self.position().distanceTo(eyePos);
					Vec3 lookDir = Vec3.directionFromRotation(newPitch, newYaw);
					setPosition(eyePos.subtract(lookDir.scale(distance)));
				}
				setRotation(newYaw, newPitch);
			}
		}
	}

	/**
	 * Turns off vanilla's occlusion-culling BFS while Freecam is active, so flying underground
	 * shows the terrain that's actually loaded instead of a few sections around the camera.
	 *
	 * <p>Vanilla decides which chunk sections to draw by walking a visibility graph outward from
	 * the section the camera is in, stepping only through faces that can see each other. Rooted
	 * inside solid rock that walk immediately reaches nothing, so almost nothing renders — which is
	 * precisely the situation a freecam flying underground is in. Vanilla already knows about this
	 * failure mode and already has the fix: a few lines above, {@code extractRenderState} clears
	 * {@code smartCull} when a <em>spectator</em> is inside a solid block, for exactly this reason.
	 * Freecam is the same geometry with a non-spectator player, so it needs the same treatment and
	 * nothing more clever.
	 *
	 * <p>Applied for the whole Freecam session rather than only when the camera happens to be
	 * inside a solid block: an enclosed cave, a one-block gap, or the far side of a wall all
	 * produce the same near-empty graph without the camera being in a block at all, and toggling
	 * the flag as the camera drifts in and out of terrain would make sections pop in and out.
	 *
	 * <p>Frustum culling and render distance are untouched — this only stops vanilla additionally
	 * hiding sections it thinks you can't see through. That is strictly more geometry submitted, so
	 * it costs some frame time; it is scoped to Freecam being on and reverts the moment it is off.
	 */
	@Inject(method = "extractRenderState", at = @At("RETURN"))
	private void gamma$disableOcclusionCullingDuringFreecam(CameraRenderState state, float partialTick, CallbackInfo ci) {
		Freecam freecam = Freecam.instance;
		if (freecam != null && freecam.isEnabled()) {
			state.smartCull = false;
		}
	}
}

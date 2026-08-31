package dev.gamma.modules.render;

import dev.gamma.Gamma;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.DoubleSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.core.event.Subscription;
import dev.gamma.core.event.events.TickEvent;
import net.minecraft.client.Minecraft;

/**
 * Hold-to-zoom with a smooth FOV lerp and scroll adjustment while held. Scroll capture needs
 * {@link dev.gamma.mixin.render.MouseHandlerMixin} — there's no Fabric API event for raw scroll
 * delta, and unlike key state it can't be polled (it's a delta, not a level).
 */
public final class Zoom extends Module {

	public static volatile Zoom instance;

	// Vanilla's options.fov clamps to [30, 110] and silently rejects (not clamps) anything
	// outside that -- OptionInstance.set() falls back to the current value on an invalid one. A
	// target below 30 made the smoothing lerp walk straight through the floor and get stuck
	// there forever, since every subsequent .set() call below 30 was a no-op. Matching this
	// setting's range to vanilla's actual bounds keeps the target always reachable.
	private final DoubleSetting zoomFov = register(new DoubleSetting("ZoomFov", "Target FOV in degrees while zoomed.", 30.0, 30.0, 110.0));
	private final DoubleSetting smoothing = register(new DoubleSetting("Smoothing", "Higher = faster transition into/out of zoom.", 0.35, 0.05, 1.0));
	private final BoolSetting cinematic = register(new BoolSetting("Cinematic", "Slow the effective mouse sensitivity while zoomed, for smoother panning.", true));

	private double currentFov = -1;
	private Integer previousVanillaFov;
	private Subscription tickSubscription;

	public Zoom() {
		super("Zoom", "Smooth scroll-adjustable zoom.", Category.RENDER);
		instance = this;
	}

	@Override
	protected void onEnable() {
		currentFov = -1;
		tickSubscription = listen(TickEvent.class, this::onTick);
	}

	@Override
	protected void onDisable() {
		Gamma.EVENT_BUS.unsubscribe(tickSubscription);
		restoreFov();
		// Belt and braces alongside the disabled-module guard in Module.guarded: leaving the
		// zoomed value here meant the next enable lerped down from a stale FOV instead of from
		// wherever vanilla actually is.
		currentFov = -1;
	}

	/** Called from {@link dev.gamma.mixin.render.MouseHandlerMixin} while held; returns whether it consumed the scroll. */
	public boolean adjustOnScroll(double yOffset) {
		if (!isEnabled()) {
			return false;
		}
		double next = zoomFov.get() - yOffset * 2.0;
		zoomFov.set(next);
		return true;
	}

	public boolean cinematicActive() {
		return isEnabled() && cinematic.get();
	}

	private void onTick(TickEvent event) {
		if (event.phase() != TickEvent.Phase.END) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (previousVanillaFov == null) {
			previousVanillaFov = client.options.fov().get();
		}
		if (currentFov < 0) {
			currentFov = previousVanillaFov;
		}
		currentFov += (zoomFov.get() - currentFov) * smoothing.get();
		client.options.fov().set((int) Math.round(Math.clamp(currentFov, 30.0, 110.0)));
	}

	private void restoreFov() {
		if (previousVanillaFov != null) {
			Minecraft.getInstance().options.fov().set(previousVanillaFov);
			previousVanillaFov = null;
		}
	}
}

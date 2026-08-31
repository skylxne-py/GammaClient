package dev.gamma.modules.render;

import dev.gamma.Gamma;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.core.event.Subscription;
import dev.gamma.core.event.events.TickEvent;
import net.minecraft.client.Minecraft;

/**
 * Named "Gamma" as a wink at the client — see the project conventions. Plain gamma-slider override needs no
 * mixin ({@code Options.gamma()} is a public {@code OptionInstance}); night vision mode does,
 * since there's no way to fake having the actual Night Vision potion effect from the client —
 * see {@link dev.gamma.mixin.render.LightmapRenderStateExtractorMixin}.
 */
public final class Fullbright extends Module {

	public static volatile Fullbright instance;

	private static final double MAX_GAMMA = 1.0;

	private final BoolSetting nightVision = register(new BoolSetting("NightVisionMode", "Also force the night-vision lightmap boost, brightening zero-light areas gamma alone can't.", false));

	private Double previousGamma;
	private Subscription tickSubscription;

	public Fullbright() {
		super("Fullbright", "Overrides brightness to maximum, with an optional night-vision mode.", Category.RENDER);
		instance = this;
	}

	@Override
	protected void onEnable() {
		Minecraft client = Minecraft.getInstance();
		previousGamma = client.options.gamma().get();
		client.options.gamma().set(MAX_GAMMA);
		tickSubscription = listen(TickEvent.class, this::onTick);
	}

	@Override
	protected void onDisable() {
		Gamma.EVENT_BUS.unsubscribe(tickSubscription);
		if (previousGamma != null) {
			Minecraft.getInstance().options.gamma().set(previousGamma);
			previousGamma = null;
		}
	}

	public boolean nightVisionActive() {
		return isEnabled() && nightVision.get();
	}

	private void onTick(TickEvent event) {
		if (event.phase() == TickEvent.Phase.END) {
			Minecraft.getInstance().options.gamma().set(MAX_GAMMA);
		}
	}
}

package dev.gamma.modules.render;

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
import net.minecraft.client.Minecraft;

/**
 * Custom sky/fog color, fog density, time of day and weather — all client-local, applied every
 * tick so they stick regardless of what the server is actually simulating. No mixin needed for
 * time-of-day/weather: {@code ClientLevel.ClientLevelData.setGameTime} and
 * {@code Level.setRainLevel}/{@code setThunderLevel} are already public vanilla API. Fog color
 * and density go through {@link dev.gamma.mixin.render.FogRendererMixin}, since
 * {@code FogRenderer.setupFog}'s output isn't otherwise reachable client-side.
 */
public final class Ambience extends Module {

	public static volatile Ambience instance;

	/**
	 * Thinnest fog the Density slider can express, and Gamma's unconditional default — see
	 * {@link #densityFactor()}. Applied as a divisor on vanilla's fog end distances, so 0.1 pushes
	 * them out 10×, which at any normal render distance means you simply never reach the fog.
	 */
	public static final double MIN_DENSITY = 0.1;

	private final BoolSetting overrideFogColor = register(new BoolSetting("OverrideFogColor", "Override the sky/fog color.", true));
	private final ColorSetting fogColor = register(new ColorSetting("FogColor", "Sky/fog color.", 0xFF88AACC));
	private final BoolSetting overrideDensity = register(new BoolSetting("OverrideDensity", "Take manual control of fog density. Leave off to keep Gamma's default thinnest fog.", false));
	private final DoubleSetting density = register(new DoubleSetting("Density", "1.0 = vanilla density, lower is thinner, higher is denser. Only applies with OverrideDensity on; at the minimum it matches the default.", 1.0, MIN_DENSITY, 4.0));

	private final EnumSetting<TimeMode> timeMode = register(new EnumSetting<>("TimeMode", "Force a fixed time of day.", TimeMode.class, TimeMode.VANILLA));
	private final IntSetting customTime = register(new IntSetting("CustomTime", "Ticks (0-23999) when TimeMode is CUSTOM.", 6000, 0, 23999));

	private final EnumSetting<WeatherMode> weatherMode = register(new EnumSetting<>("WeatherMode", "Force clear/rain/storm regardless of the server's actual weather.", WeatherMode.class, WeatherMode.VANILLA));

	private Subscription tickSubscription;

	public Ambience() {
		super("Ambience", "Overrides sky/fog color, fog density, time of day and weather.", Category.RENDER);
		instance = this;
	}

	@Override
	protected void onEnable() {
		tickSubscription = listen(TickEvent.class, this::onTick);
	}

	@Override
	protected void onDisable() {
		Gamma.EVENT_BUS.unsubscribe(tickSubscription);
	}

	public boolean overridesFogColor() {
		return isEnabled() && overrideFogColor.get();
	}

	public int fogColorArgb() {
		return fogColor.get();
	}

	/**
	 * Fog density divisor, applied unconditionally — this module being disabled is not an escape
	 * hatch from it.
	 *
	 * <p>Thinnest-possible fog is the default because dense fog is actively hostile to what this
	 * client is for: hunting bases means seeing a long way underground, and vanilla fog hides
	 * exactly that. The only way to get denser fog back is to deliberately ask for it — enable
	 * Ambience, turn OverrideDensity on, and move Density off its minimum. Anything short of all
	 * three lands back on {@link #MIN_DENSITY}, so a half-configured Ambience can't quietly
	 * restore the fog you were trying to get rid of.
	 */
	public static double densityFactor() {
		Ambience ambience = instance;
		if (ambience != null && ambience.isEnabled() && ambience.overrideDensity.get()) {
			return ambience.density.get();
		}
		return MIN_DENSITY;
	}

	private void onTick(TickEvent event) {
		if (event.phase() != TickEvent.Phase.END) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return;
		}
		if (timeMode.get() == TimeMode.CUSTOM) {
			client.level.getLevelData().setGameTime(customTime.get());
		}
		switch (weatherMode.get()) {
			case CLEAR -> {
				client.level.setRainLevel(0.0f);
				client.level.setThunderLevel(0.0f);
			}
			case RAIN -> {
				client.level.setRainLevel(1.0f);
				client.level.setThunderLevel(0.0f);
			}
			case STORM -> {
				client.level.setRainLevel(1.0f);
				client.level.setThunderLevel(1.0f);
			}
			case VANILLA -> {
			}
		}
	}

	public enum TimeMode {
		VANILLA,
		CUSTOM
	}

	public enum WeatherMode {
		VANILLA,
		CLEAR,
		RAIN,
		STORM
	}
}

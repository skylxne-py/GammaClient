package dev.gamma.modules.render;

import dev.gamma.Gamma;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.core.event.Subscription;
import dev.gamma.core.event.events.TickEvent;
import dev.gamma.mixin.render.FogRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.Portal;

/**
 * One module, granular toggles — each backed by the narrowest real hook found for it (see each
 * mixin's own doc comment): fog ({@link FogRendererAccessor}, a static field flip — reapplied
 * every tick since {@code Setting} has no change-listener to hook), weather
 * ({@code WeatherEffectRendererMixin}), fire/water/block/pumpkin overlay
 * ({@code ScreenEffectRendererMixin}), hurt cam/view bobbing ({@code GameRendererMixin}),
 * darkness/blindness ({@code MobEffectFogEnvironmentMixin}), totem animation
 * ({@code TotemParticleMixin}), armor ({@code HumanoidArmorLayerMixin}), item entity
 * spin ({@code ItemEntityMixin}). Nausea and portal overlay need no mixin at all — both ride
 * vanilla's own "Distortion Effects" accessibility slider ({@code Options.screenEffectScale()}),
 * which already scales exactly this wobble to zero.
 */
public final class NoRender extends Module {

	public static volatile NoRender instance;

	private final BoolSetting fog = register(new BoolSetting("Fog", "Render fog.", true));
	private final BoolSetting weather = register(new BoolSetting("Weather", "Render falling weather at all. Off hides both; leave it on to pick per type below.", true));
	private final BoolSetting rain = register(new BoolSetting("Rain", "Render falling rain.", true, () -> instance == null || instance.weather.get()));
	private final BoolSetting snow = register(new BoolSetting("Snow", "Render falling snow.", true, () -> instance == null || instance.weather.get()));
	private final BoolSetting fireOverlay = register(new BoolSetting("FireOverlay", "Render the on-fire screen overlay.", true));
	private final BoolSetting waterOverlay = register(new BoolSetting("WaterOverlay", "Render the underwater screen overlay.", true));
	private final BoolSetting blockOverlay = register(new BoolSetting("BlockOverlay", "Render the in-block screen overlay.", true));
	private final BoolSetting pumpkinOverlay = register(new BoolSetting("PumpkinOverlay", "Render the carved-pumpkin screen overlay specifically.", true));
	private final BoolSetting hurtCam = register(new BoolSetting("HurtCam", "Render the damage camera-shake.", true));
	private final BoolSetting viewBobbing = register(new BoolSetting("ViewBobbing", "Render view bobbing (independent of the vanilla option).", true));
	private final BoolSetting nausea = register(new BoolSetting("Nausea", "Render the Nausea/Confusion effect's screen distortion.", true));
	private final BoolSetting darkness = register(new BoolSetting("Darkness", "Render the Darkness effect's vignette.", true));
	private final BoolSetting blindness = register(new BoolSetting("Blindness", "Render the Blindness effect's vignette.", true));
	private final BoolSetting portalOverlay = register(new BoolSetting("PortalOverlay", "Render the portal-induced screen distortion specifically.", true));
	private final BoolSetting totemAnimation = register(new BoolSetting("TotemAnimation", "Render the totem-of-undying particle burst.", true));
	private final BoolSetting armor = register(new BoolSetting("Armor", "Render armor models on entities.", true));
	private final BoolSetting itemSpin = register(new BoolSetting("ItemSpin", "Spin dropped item entities.", true));

	private Double previousScreenEffectScale;
	private Subscription tickSubscription;

	public NoRender() {
		super("NoRender", "Granular toggles for fog, weather, overlays, camera effects and more.", Category.RENDER);
		instance = this;
	}

	@Override
	protected void onEnable() {
		previousScreenEffectScale = Minecraft.getInstance().options.screenEffectScale().get();
		tickSubscription = listen(TickEvent.class, this::onTick);
	}

	@Override
	protected void onDisable() {
		Gamma.EVENT_BUS.unsubscribe(tickSubscription);
		FogRendererAccessor.gamma$setFogEnabled(true);
		if (previousScreenEffectScale != null) {
			Minecraft.getInstance().options.screenEffectScale().set(previousScreenEffectScale);
			previousScreenEffectScale = null;
		}
	}

	private void onTick(TickEvent event) {
		if (event.phase() != TickEvent.Phase.END) {
			return;
		}
		FogRendererAccessor.gamma$setFogEnabled(fog.get());

		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		boolean inPortal = player != null && player.level().getBlockState(player.blockPosition()).getBlock() instanceof Portal;
		double scale = previousScreenEffectScale == null ? 1.0 : previousScreenEffectScale;
		if (!nausea.get() || (inPortal && !portalOverlay.get())) {
			scale = 0.0;
		}
		client.options.screenEffectScale().set(scale);
	}

	/** True when anything falls at all — the cheap "skip the whole pass" test. */
	public boolean weatherEnabled() {
		return !isEnabled() || weather.get();
	}

	public boolean rainEnabled() {
		return !isEnabled() || (weather.get() && rain.get());
	}

	public boolean snowEnabled() {
		return !isEnabled() || (weather.get() && snow.get());
	}

	public boolean fireOverlayEnabled() {
		return !isEnabled() || fireOverlay.get();
	}

	public boolean waterOverlayEnabled() {
		return !isEnabled() || waterOverlay.get();
	}

	public boolean blockOverlayEnabled() {
		return !isEnabled() || blockOverlay.get();
	}

	public boolean pumpkinOverlayEnabled() {
		return !isEnabled() || pumpkinOverlay.get();
	}

	public boolean hurtCamEnabled() {
		return !isEnabled() || hurtCam.get();
	}

	public boolean viewBobbingEnabled() {
		return !isEnabled() || viewBobbing.get();
	}

	public boolean darknessEnabled() {
		return !isEnabled() || darkness.get();
	}

	public boolean blindnessEnabled() {
		return !isEnabled() || blindness.get();
	}

	public boolean totemAnimationEnabled() {
		return !isEnabled() || totemAnimation.get();
	}

	public boolean armorEnabled() {
		return !isEnabled() || armor.get();
	}

	public boolean itemSpinEnabled() {
		return !isEnabled() || itemSpin.get();
	}
}

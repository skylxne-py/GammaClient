package dev.gamma.modules.render;

import dev.gamma.Gamma;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.ColorSetting;
import dev.gamma.config.setting.DoubleSetting;
import dev.gamma.config.setting.EnumSetting;
import dev.gamma.config.setting.StringSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.core.event.Subscription;
import dev.gamma.core.event.events.WorldRenderExtractEvent;
import dev.gamma.render.RenderPass;
import dev.gamma.render.Renderer3D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * A small client tag floating above your own head, visible whenever the camera is far enough back
 * to see your head at all — i.e. third person, or Freecam.
 *
 * <p>Purely local: nothing here is sent anywhere and no other client can see it. Vanilla never
 * draws your own nametag (you are the one player whose name you don't need told), so there is
 * nothing to collide with and nothing to hide — this is a new label, not an override of one.
 *
 * <h2>Why it gates on the camera rather than always drawing</h2>
 *
 * <p>In first person the tag would sit directly behind the crosshair at head height, since your
 * head is where the camera is: a permanent smear across the middle of the screen. The check is
 * {@code CameraType.isFirstPerson()} on the options rather than a Freecam-aware special case,
 * because Freecam already switches the camera type itself.
 */
public final class NameTag extends Module {

	/** {@code TextGizmo.Style.DEFAULT_SCALE} — what an unstyled billboard label draws at. */
	private static final float GIZMO_DEFAULT_SCALE = 0.32f;

	/**
	 * Decoration applied to the tag text.
	 *
	 * <p>The font itself can't be changed — it is Minecraft's own bitmap font and swapping it means
	 * shipping a font plus a resource pack to provide it — so "less plain" has to come from letter
	 * spacing and framing, which is what these do. {@link #SPACED} is the default because tracked-out
	 * capitals are the single change that most stops a label reading like debug text.
	 */
	public enum Style {
		PLAIN,
		/** {@code G A M M A} — letter-spaced capitals. */
		SPACED,
		/** {@code [ Gamma ]} */
		BRACKETS,
		/** {@code » Gamma «} */
		ARROWS,
		/** {@code · Gamma ·} */
		DOTS;

		String apply(String base) {
			return switch (this) {
				case PLAIN -> base;
				case SPACED -> String.join(" ", base.toUpperCase(java.util.Locale.ROOT).split(""));
				case BRACKETS -> "[ " + base + " ]";
				case ARROWS -> "» " + base + " «";
				case DOTS -> "· " + base + " ·";
			};
		}
	}

	private final StringSetting text = register(new StringSetting("Text", "The tag to draw. Blank uses the client name.", ""));
	private final EnumSetting<Style> style = register(new EnumSetting<>("Style", "How the tag is dressed up. The font can't change, so this is spacing and framing.", Style.class, Style.SPACED));
	private final ColorSetting color = register(new ColorSetting("Color", "Tag color. Defaults to the watermark's accent blue.", 0xFF788CFF));
	private final DoubleSetting scale = register(new DoubleSetting("Scale", "Text size. " + GIZMO_DEFAULT_SCALE + " is what the ESP labels draw at, so keep it under that to stay subtle.", 0.20, 0.05, 1.0));
	private final DoubleSetting height = register(new DoubleSetting("Height", "Blocks above the top of your head.", 0.55, 0.0, 3.0));
	private final BoolSetting throughWalls = register(new BoolSetting("ThroughWalls", "Draw the tag even when something is between it and the camera.", false));

	private Subscription subscription;

	public NameTag() {
		super("NameTag", "Shows a small client tag above your head in third person.", Category.RENDER);
	}

	@Override
	protected void onEnable() {
		subscription = listen(WorldRenderExtractEvent.class, this::onExtract);
	}

	@Override
	protected void onDisable() {
		Gamma.EVENT_BUS.unsubscribe(subscription);
	}

	private void onExtract(WorldRenderExtractEvent event) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null || client.options.getCameraType().isFirstPerson()) {
			return;
		}
		float partialTick = event.context().deltaTracker().getGameTimeDeltaPartialTick(false);
		// Interpolated, not player.position(): the raw position is last tick's, so the tag would
		// lag a moving head by up to one tick and visibly swim behind it.
		Vec3 feet = player.getPosition(partialTick);
		Vec3 anchor = new Vec3(feet.x, feet.y + player.getBbHeight() + height.get(), feet.z);
		RenderPass pass = throughWalls.get() ? RenderPass.THROUGH_WALLS : RenderPass.DEPTH_TESTED;
		Renderer3D.text3d(anchor, label(), color.get(), scale.get().floatValue(), pass);
	}

	private String label() {
		String custom = text.get().trim();
		return style.get().apply(custom.isEmpty() ? "Gamma" : custom);
	}
}

package dev.gamma.modules.render;

import dev.gamma.Gamma;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.ColorSetting;
import dev.gamma.config.setting.DoubleSetting;
import dev.gamma.config.setting.EnumSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.core.event.Subscription;
import dev.gamma.core.event.events.WorldRenderExtractEvent;
import dev.gamma.render.RenderPass;
import dev.gamma.render.Renderer3D;
import dev.gamma.render.ShapeBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Restyles the vanilla targeted-block outline. Suppresses vanilla's own via the public
 * {@code GameRenderer.setRenderBlockOutline(false)} (no mixin needed — it's already there for
 * spectator mode) and draws a replacement through {@link Renderer3D} instead.
 */
public final class BlockHighlight extends Module {

	private final ColorSetting strokeColor = register(new ColorSetting("StrokeColor", "Outline color.", 0xFF000000));
	private final ColorSetting fillColor = register(new ColorSetting("FillColor", "Fill color.", 0x40FFFFFF));
	private final DoubleSetting width = register(new DoubleSetting("Width", "Outline width.", 2.0, 0.5, 8.0));
	private final BoolSetting fill = register(new BoolSetting("Fill", "Draw the fill in addition to the outline.", true));
	private final EnumSetting<RenderPass> pass = register(new EnumSetting<>("Pass", "Depth-test behavior (\"chams\" is THROUGH_WALLS).", RenderPass.class, RenderPass.DEPTH_TESTED));

	private Subscription extractSubscription;

	public BlockHighlight() {
		super("BlockHighlight", "Restyles the targeted-block outline: color, width, fill, chams.", Category.RENDER);
	}

	@Override
	protected void onEnable() {
		Minecraft.getInstance().gameRenderer.setRenderBlockOutline(false);
		extractSubscription = listen(WorldRenderExtractEvent.class, this::onExtract);
	}

	@Override
	protected void onDisable() {
		Gamma.EVENT_BUS.unsubscribe(extractSubscription);
		Minecraft.getInstance().gameRenderer.setRenderBlockOutline(true);
	}

	private void onExtract(WorldRenderExtractEvent event) {
		HitResult hitResult = Minecraft.getInstance().hitResult;
		if (!(hitResult instanceof BlockHitResult blockHit) || hitResult.getType() == HitResult.Type.MISS) {
			return;
		}
		AABB box = ShapeBuilder.blockOutline(blockHit.getBlockPos());
		int stroke = strokeColor.get();
		if (fill.get()) {
			Renderer3D.outlinedBox(box, stroke, fillColor.get(), width.get().floatValue(), pass.get());
		} else {
			Renderer3D.box(box, stroke, width.get().floatValue(), pass.get());
		}
	}
}

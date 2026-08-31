package dev.gamma.render;

import dev.gamma.Gamma;
import dev.gamma.core.event.events.WorldRenderExtractEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Backs {@code .gamma smoketest} (the project conventions "Testing"): while active, draws one of every
 * {@link Renderer3D} shape in every {@link RenderPass}, laid out near world spawn — one
 * column per pass, one row per shape. Toggled by {@code GammaCommands}.
 */
public final class SmoketestRenderer {

	private static final int STROKE_COLOR = 0xFFFF3355;
	private static final int FILL_COLOR = 0x8000AAFF;
	private static final int LINE_COLOR = 0xFFFFFF00;
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final double COLUMN_SPACING = 6.0;
	private static final double ROW_SPACING = 3.0;

	private boolean active;

	public void install() {
		Gamma.EVENT_BUS.subscribe(WorldRenderExtractEvent.class, this::onExtract);
	}

	public void toggle() {
		active = !active;
	}

	public boolean isActive() {
		return active;
	}

	private void onExtract(WorldRenderExtractEvent event) {
		if (!active) {
			return;
		}
		BlockPos spawn = event.context().level().getRespawnData().pos();
		Vec3 base = Vec3.atBottomCenterOf(spawn).add(0, 1, 0);
		Vec3 eye = ShapeBuilder.tracerOrigin(event.context().camera());

		RenderPass[] passes = RenderPass.values();
		for (int column = 0; column < passes.length; column++) {
			drawColumn(base.x + column * COLUMN_SPACING, base.y, base.z, eye, passes[column]);
		}
	}

	private void drawColumn(double x, double y, double z, Vec3 eye, RenderPass pass) {
		int row = 0;

		Renderer3D.box(rowBox(x, y, z, row), STROKE_COLOR, pass);
		row++;

		Renderer3D.filledBox(rowBox(x, y, z, row), FILL_COLOR, pass);
		row++;

		Renderer3D.outlinedBox(rowBox(x, y, z, row), STROKE_COLOR, FILL_COLOR, pass);
		row++;

		double lineZ = z + row * ROW_SPACING;
		Renderer3D.line(new Vec3(x - 0.5, y + 0.5, lineZ), new Vec3(x + 0.5, y + 1.5, lineZ), LINE_COLOR, pass);
		row++;

		Vec3 tracerTarget = new Vec3(x, y + 1, z + row * ROW_SPACING);
		Renderer3D.tracer(eye, tracerTarget, LINE_COLOR, pass);
		row++;

		double quadZ = z + row * ROW_SPACING;
		Renderer3D.quad(
				new Vec3(x - 0.5, y, quadZ - 0.5), new Vec3(x + 0.5, y, quadZ - 0.5),
				new Vec3(x + 0.5, y, quadZ + 0.5), new Vec3(x - 0.5, y, quadZ + 0.5),
				FILL_COLOR, pass);
		row++;

		Renderer3D.text3d(new Vec3(x, y + 1.5, z + row * ROW_SPACING), pass.name(), TEXT_COLOR, pass);
	}

	private static AABB rowBox(double x, double y, double z, int row) {
		double rowZ = z + row * ROW_SPACING;
		return new AABB(x - 0.5, y, rowZ - 0.5, x + 0.5, y + 1, rowZ + 0.5);
	}
}

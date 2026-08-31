package dev.gamma.gui.hud;

import dev.gamma.core.keybind.TextInputCapture;
import dev.gamma.gui.GammaScreen;
import dev.gamma.gui.clickgui.ColorPickerPopup;
import dev.gamma.gui.clickgui.Theme;
import dev.gamma.render.Renderer2D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

import static com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE;

/**
 * Drag any enabled HUD element to move it, with snapping to screen edges/center and to other
 * elements' edges; scroll a hovered element to scale it; right-click cycles its color through a
 * small palette or opens the full picker on a second right-click. Closing (Esc) persists.
 */
public final class HudEditorScreen extends GammaScreen implements TextInputCapture {

	private static final int SNAP_DISTANCE = 6;
	private static final Theme PICKER_THEME = new Theme();
	private static final int TOGGLE_LIST_X = 8;
	private static final int TOGGLE_LIST_Y = 8;
	private static final int TOGGLE_ROW_HEIGHT = 18;
	private static final int TOGGLE_LIST_WIDTH = 170;
	private static final int TOGGLE_PILL_WIDTH = 22;
	private static final int TOGGLE_PILL_HEIGHT = 11;

	/** How long after a scroll further scrolls keep targeting the same element — see {@link #mouseScrolled}. */
	private static final long SCALE_LATCH_MILLIS = 700;

	private final HudManager hudManager;
	private HudComponent dragging;
	/**
	 * Where the pointer has actually dragged the element to, before snapping.
	 *
	 * <p>Kept separately from {@link #dragX}/{@link #dragY} because snapping used to be applied by
	 * overwriting the drag position itself. That silently discarded the movement that had been
	 * accumulated: once within the snap distance of an edge, every subsequent small mouse movement
	 * was added to the *snapped* value and immediately snapped back, so an element could only be
	 * pulled off an edge by a single mouse event larger than the snap distance — which at a high
	 * mouse polling rate and a GUI scale of 3 essentially never happens. That is the "elements are
	 * stuck to the sides" bug. Deltas now accumulate here where nothing overwrites them, and the
	 * snapped values are derived for drawing and for the final commit.
	 */
	private double rawDragX;
	private double rawDragY;
	private double dragX;
	private double dragY;
	private int dragWidth;
	private int dragHeight;
	private ColorPickerPopup colorPopup;
	private HudComponent scaling;
	private boolean toggleListCollapsed;
	private long lastScaleMillis;

	public HudEditorScreen(HudManager hudManager) {
		super(Component.literal("Gamma HUD Editor"));
		this.hudManager = hudManager;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		Renderer2D renderer = new Renderer2D(extractor);
		renderer.setTextShadow(false);
		HudContext ctx = hudManager.buildContext(width, height, partialTick);
		renderer.fill(0, 0, width, height, 0x55000000);

		renderToggleList(renderer, mouseX, mouseY);

		for (HudComponent component : hudManager.all()) {
			if (!component.isEnabled()) {
				continue;
			}
			HudManager.Bounds bounds;
			if (component == dragging) {
				bounds = new HudManager.Bounds((int) Math.round(dragX), (int) Math.round(dragY), dragWidth, dragHeight);
				var pose = extractor.pose();
				pose.pushMatrix();
				pose.translate((float) bounds.x(), (float) bounds.y());
				pose.scale((float) component.scale());
				component.render(renderer, font, 0, 0, ctx);
				pose.popMatrix();
			} else {
				bounds = HudManager.resolveBounds(component, font, ctx);
				HudManager.renderComponent(extractor, renderer, font, component, ctx);
			}
			if (bounds.width() <= 0 || bounds.height() <= 0) {
				continue;
			}
			boolean hovered = contains(bounds, mouseX, mouseY);
			int outlineColor = component == dragging ? 0xFF788CFF : (hovered ? 0xB0FFFFFF : 0x50FFFFFF);
			renderer.roundedRectOutline(bounds.x() - 2, bounds.y() - 2, bounds.width() + 4, bounds.height() + 4, 3, 1.2f, outlineColor);

			// Readout while scaling: the element resizing is the only other feedback there is, and
			// at the low end a notch moves it by a couple of pixels, which reads as nothing happening.
			if (component == scaling && System.currentTimeMillis() - lastScaleMillis < SCALE_LATCH_MILLIS) {
				String label = Math.round(component.scale() * 100) + "%";
				renderer.text(font, label, bounds.x() + bounds.width() + 6, bounds.y() - 2, 0xFF788CFF);
			}
		}

		renderer.text(font, "Drag to move (Shift disables snapping), scroll to scale, right-click to cycle color, middle-click for the full picker. Esc to finish.", 8, height - 12, 0xFFAAAAAA);

		if (colorPopup != null) {
			colorPopup.clampToScreen(width, height);
			colorPopup.render(renderer, font);
		}
	}

	private boolean contains(HudManager.Bounds bounds, double mouseX, double mouseY) {
		return mouseX >= bounds.x() && mouseX < bounds.x() + bounds.width() && mouseY >= bounds.y() && mouseY < bounds.y() + bounds.height();
	}

	/**
	 * Every element defaults to enabled with no other UI to turn one off -- this is the only place
	 * that's exposed.
	 *
	 * <p>Collapsible, and that is not decoration. Expanded it covers the whole top-left corner of
	 * the screen, and it is checked before elements are hit-tested — so the watermark, the module
	 * list and anything else parked up there could not be clicked or dragged at all, which looks
	 * exactly like an element being stuck. Collapsing leaves a single header bar and frees the
	 * corner.
	 */
	private void renderToggleList(Renderer2D renderer, int mouseX, int mouseY) {
		List<HudComponent> all = hudManager.all();
		int listHeight = toggleListHeight();
		renderer.roundedRect(TOGGLE_LIST_X, TOGGLE_LIST_Y, TOGGLE_LIST_WIDTH, listHeight, 6, 0xB0181820);

		boolean headerHovered = mouseX >= TOGGLE_LIST_X && mouseX < TOGGLE_LIST_X + TOGGLE_LIST_WIDTH
				&& mouseY >= TOGGLE_LIST_Y && mouseY < TOGGLE_LIST_Y + TOGGLE_ROW_HEIGHT;
		renderer.text(font, "HUD ELEMENTS", TOGGLE_LIST_X + 6, TOGGLE_LIST_Y + (TOGGLE_ROW_HEIGHT - font.lineHeight) / 2,
				headerHovered ? 0xFFFFFFFF : 0xFF9A9AA4);
		String chevron = toggleListCollapsed ? ">" : "v";
		renderer.text(font, chevron, TOGGLE_LIST_X + TOGGLE_LIST_WIDTH - 14,
				TOGGLE_LIST_Y + (TOGGLE_ROW_HEIGHT - font.lineHeight) / 2, 0xFF9A9AA4);
		if (toggleListCollapsed) {
			return;
		}

		int y = TOGGLE_LIST_Y + TOGGLE_ROW_HEIGHT + 2;
		for (HudComponent component : all) {
			boolean hovered = mouseX >= TOGGLE_LIST_X && mouseX < TOGGLE_LIST_X + TOGGLE_LIST_WIDTH && mouseY >= y && mouseY < y + TOGGLE_ROW_HEIGHT;
			if (hovered) {
				renderer.fill(TOGGLE_LIST_X + 2, y, TOGGLE_LIST_X + TOGGLE_LIST_WIDTH - 2, y + TOGGLE_ROW_HEIGHT, 0x30FFFFFF);
			}
			renderer.text(font, component.displayName(), TOGGLE_LIST_X + 6, y + (TOGGLE_ROW_HEIGHT - font.lineHeight) / 2, 0xFFE8E8EC);
			int pillX = TOGGLE_LIST_X + TOGGLE_LIST_WIDTH - TOGGLE_PILL_WIDTH - 6;
			int pillY = y + (TOGGLE_ROW_HEIGHT - TOGGLE_PILL_HEIGHT) / 2;
			int pillColor = component.isEnabled() ? 0xFF788CFF : 0xFF3C3D44;
			renderer.roundedRect(pillX, pillY, TOGGLE_PILL_WIDTH, TOGGLE_PILL_HEIGHT, TOGGLE_PILL_HEIGHT / 2, pillColor);
			int knobX = component.isEnabled() ? pillX + TOGGLE_PILL_WIDTH - TOGGLE_PILL_HEIGHT + 1 : pillX + 1;
			renderer.circle(knobX + (TOGGLE_PILL_HEIGHT - 2) / 2, pillY + TOGGLE_PILL_HEIGHT / 2, (TOGGLE_PILL_HEIGHT - 2) / 2, 0xFFF0F0F5);
			y += TOGGLE_ROW_HEIGHT;
		}
	}

	private int toggleListHeight() {
		return toggleListCollapsed
				? TOGGLE_ROW_HEIGHT + 4
				: TOGGLE_ROW_HEIGHT + 2 + hudManager.all().size() * TOGGLE_ROW_HEIGHT + 4;
	}

	private boolean toggleListClicked(double mouseX, double mouseY) {
		List<HudComponent> all = hudManager.all();
		if (mouseX < TOGGLE_LIST_X || mouseX >= TOGGLE_LIST_X + TOGGLE_LIST_WIDTH
				|| mouseY < TOGGLE_LIST_Y || mouseY >= TOGGLE_LIST_Y + toggleListHeight()) {
			return false;
		}
		if (mouseY < TOGGLE_LIST_Y + TOGGLE_ROW_HEIGHT) {
			toggleListCollapsed = !toggleListCollapsed;
			return true;
		}
		int index = (int) ((mouseY - TOGGLE_LIST_Y - TOGGLE_ROW_HEIGHT - 2) / TOGGLE_ROW_HEIGHT);
		if (index < 0 || index >= all.size()) {
			return true;
		}
		HudComponent component = all.get(index);
		component.setEnabled(!component.isEnabled());
		hudManager.requestSave();
		return true;
	}

	private HudComponent componentAt(double mouseX, double mouseY, HudContext ctx) {
		List<HudComponent> all = hudManager.all();
		for (int i = all.size() - 1; i >= 0; i--) {
			HudComponent component = all.get(i);
			if (!component.isEnabled()) {
				continue;
			}
			HudManager.Bounds bounds = HudManager.resolveBounds(component, font, ctx);
			if (bounds.width() > 0 && bounds.height() > 0 && contains(bounds, mouseX, mouseY)) {
				return component;
			}
		}
		return null;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (colorPopup != null) {
			if (colorPopup.mouseClicked(event, doubleClick)) {
				return true;
			}
			if (!colorPopup.contains(event.x(), event.y())) {
				colorPopup = null;
			}
			return true;
		}
		if (event.button() == 0 && toggleListClicked(event.x(), event.y())) {
			return true;
		}
		HudContext ctx = hudManager.buildContext(width, height, 1.0f);
		HudComponent hit = componentAt(event.x(), event.y(), ctx);
		if (hit == null) {
			return false;
		}
		if (event.button() == 1) {
			cycleColor(hit);
			return true;
		}
		if (event.button() == 2) {
			HudManager.Bounds bounds = HudManager.resolveBounds(hit, font, ctx);
			openFullPicker(hit, bounds.x(), bounds.y() + bounds.height());
			return true;
		}
		if (event.button() == 0) {
			HudManager.Bounds bounds = HudManager.resolveBounds(hit, font, ctx);
			dragging = hit;
			rawDragX = bounds.x();
			rawDragY = bounds.y();
			dragX = rawDragX;
			dragY = rawDragY;
			dragWidth = bounds.width();
			dragHeight = bounds.height();
			return true;
		}
		return false;
	}

	private static final int[] COLOR_PALETTE = {0xFFFFFFFF, 0xFF788CFF, 0xFF6FCF6F, 0xFFE05050, 0xFFE0C050};

	private void cycleColor(HudComponent component) {
		int current = component.color();
		int index = 0;
		for (int i = 0; i < COLOR_PALETTE.length; i++) {
			if (COLOR_PALETTE[i] == current) {
				index = i;
				break;
			}
		}
		component.setColor(COLOR_PALETTE[(index + 1) % COLOR_PALETTE.length]);
		hudManager.requestSave();
	}

	/** A held right-click beyond the palette cycle opens the full HSV picker for precise colors. */
	private void openFullPicker(HudComponent component, int anchorX, int anchorY) {
		colorPopup = new ColorPickerPopup(component.color(), color -> {
			component.setColor(color);
			hudManager.requestSave();
		}, PICKER_THEME, anchorX, anchorY);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragXDelta, double dragYDelta) {
		if (colorPopup != null) {
			return colorPopup.mouseDragged(event, dragXDelta, dragYDelta);
		}
		if (dragging == null) {
			return false;
		}
		rawDragX += dragXDelta;
		rawDragY += dragYDelta;
		// Kept wholly on screen. An element dragged off an edge is not a layout anyone wants, and
		// it is then only recoverable by editing hud.json by hand.
		rawDragX = clamp(rawDragX, width, dragWidth);
		rawDragY = clamp(rawDragY, height, dragHeight);
		// Shift is the standard "ignore the grid" modifier, and it is also the escape hatch for
		// placing something deliberately one or two pixels off an alignment the snap would take.
		boolean snap = !event.hasShiftDown();
		dragX = snap ? snapAxis(rawDragX, width, dragWidth, true) : rawDragX;
		dragY = snap ? snapAxis(rawDragY, height, dragHeight, false) : rawDragY;
		return true;
	}

	private static double clamp(double value, int screenExtent, int elementExtent) {
		if (elementExtent >= screenExtent) {
			return value;
		}
		return Math.max(0, Math.min(screenExtent - elementExtent, value));
	}

	/**
	 * The nearest snap target to {@code raw}, or {@code raw} itself when nothing is close.
	 *
	 * <p>Pure on purpose: it reads the drag position and returns a display position, and never
	 * writes back to the accumulator. See {@link #rawDragX} for what happened when it did.
	 */
	private double snapAxis(double raw, int screenExtent, int elementExtent, boolean horizontal) {
		double best = raw;
		double bestDistance = SNAP_DISTANCE;
		for (double candidate : new double[] {0, (screenExtent - elementExtent) / 2.0, screenExtent - elementExtent}) {
			double distance = Math.abs(raw - candidate);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = candidate;
			}
		}
		HudContext ctx = hudManager.buildContext(width, height, 1.0f);
		for (HudComponent other : hudManager.all()) {
			if (other == dragging || !other.isEnabled()) {
				continue;
			}
			HudManager.Bounds bounds = HudManager.resolveBounds(other, font, ctx);
			int start = horizontal ? bounds.x() : bounds.y();
			int extent = horizontal ? bounds.width() : bounds.height();
			for (double candidate : new double[] {start, start + extent - elementExtent, start + (extent - elementExtent) / 2.0}) {
				double distance = Math.abs(raw - candidate);
				if (distance < bestDistance) {
					bestDistance = distance;
					best = candidate;
				}
			}
		}
		return best;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (colorPopup != null) {
			return colorPopup.mouseReleased(event);
		}
		if (dragging == null || event.button() != 0) {
			return false;
		}
		Anchor anchor = resolveAnchor();
		double offsetX = switch (anchor) {
			case TOP_LEFT, MIDDLE_LEFT, BOTTOM_LEFT -> dragX;
			case TOP_CENTER, MIDDLE_CENTER, BOTTOM_CENTER -> dragX - (width - dragWidth) / 2.0;
			case TOP_RIGHT, MIDDLE_RIGHT, BOTTOM_RIGHT -> width - dragWidth - dragX;
		};
		double offsetY = switch (anchor) {
			case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> dragY;
			case MIDDLE_LEFT, MIDDLE_CENTER, MIDDLE_RIGHT -> dragY - (height - dragHeight) / 2.0;
			case BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT -> height - dragHeight - dragY;
		};
		dragging.setPosition(anchor, offsetX, offsetY);
		hudManager.requestSave();
		dragging = null;
		return true;
	}

	private Anchor resolveAnchor() {
		double centerX = dragX + dragWidth / 2.0;
		double centerY = dragY + dragHeight / 2.0;
		int horizontal = centerX < width / 3.0 ? 0 : centerX < width * 2.0 / 3.0 ? 1 : 2;
		int vertical = centerY < height / 3.0 ? 0 : centerY < height * 2.0 / 3.0 ? 1 : 2;
		Anchor[][] grid = {
				{Anchor.TOP_LEFT, Anchor.TOP_CENTER, Anchor.TOP_RIGHT},
				{Anchor.MIDDLE_LEFT, Anchor.MIDDLE_CENTER, Anchor.MIDDLE_RIGHT},
				{Anchor.BOTTOM_LEFT, Anchor.BOTTOM_CENTER, Anchor.BOTTOM_RIGHT}
		};
		return grid[vertical][horizontal];
	}

	/**
	 * Scales the element under the pointer, staying with it once scrolling has started.
	 *
	 * <p>The latch is the whole reason this works. Scaling changes the element's bounds, so shrinking
	 * one pulls it out from under a stationary cursor after a notch or two and every further scroll
	 * then hit-tested to nothing — which is what "scaling doesn't really work" was. While scrolling
	 * continues, the target stays whatever was first hit; it is only re-picked after a pause.
	 *
	 * <p>The step is multiplicative rather than a flat amount because the range is 0.25 to 4. A
	 * fixed 0.05 is a fifth of the smallest size and a eightieth of the largest, so the same notch
	 * was a huge jump at one end and imperceptible at the other.
	 */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		long now = System.currentTimeMillis();
		HudComponent target = scaling;
		if (target == null || now - lastScaleMillis > SCALE_LATCH_MILLIS) {
			target = componentAt(mouseX, mouseY, hudManager.buildContext(width, height, 1.0f));
		}
		if (target == null) {
			return false;
		}
		scaling = target;
		lastScaleMillis = now;
		target.setScale(target.scale() * Math.pow(1.1, scrollY));
		hudManager.requestSave();
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		return colorPopup != null && colorPopup.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (colorPopup != null && colorPopup.keyPressed(event)) {
			return true;
		}
		if (event.key() == KEY_ESCAPE) {
			Minecraft.getInstance().gui.setScreen(null);
			return true;
		}
		return false;
	}

	@Override
	public boolean isCapturingTextInput() {
		return colorPopup != null && colorPopup.isCapturingInput();
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}
}

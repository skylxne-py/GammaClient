package dev.gamma.gui.hud.elements;

import dev.gamma.core.Module;
import dev.gamma.core.ModuleRegistry;
import dev.gamma.gui.clickgui.anim.Animated;
import dev.gamma.gui.hud.Anchor;
import dev.gamma.gui.hud.HudComponent;
import dev.gamma.gui.hud.HudContext;
import dev.gamma.render.Renderer2D;
import dev.gamma.util.ColorUtil;
import net.minecraft.client.gui.Font;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The enabled-module list, sorted widest-first, each entry fading in/out as its module toggles. */
public final class ModuleListElement extends HudComponent {

	private static final int LINE_GAP = 2;

	private final ModuleRegistry registry;
	private final Map<Module, Animated> alphas = new LinkedHashMap<>();

	public ModuleListElement(ModuleRegistry registry) {
		super("module_list", "Module List", Anchor.TOP_RIGHT, 6, 6);
		this.registry = registry;
	}

	private List<Module> sortedByWidth(Font font) {
		for (Module module : registry.all()) {
			Animated anim = alphas.computeIfAbsent(module, m -> Animated.of(0));
			anim.set(module.isEnabled() ? 1 : 0);
		}
		alphas.entrySet().removeIf(entry -> !entry.getKey().isEnabled() && entry.getValue().get() <= 0.001);

		return alphas.entrySet().stream()
				.filter(entry -> entry.getValue().get() > 0.001)
				.map(Map.Entry::getKey)
				.sorted(Comparator.comparingInt((Module m) -> font.width(m.name())).reversed())
				.toList();
	}

	@Override
	public int measureWidth(Font font, HudContext ctx) {
		return sortedByWidth(font).stream().mapToInt(m -> font.width(m.name())).max().orElse(0);
	}

	@Override
	public int measureHeight(Font font, HudContext ctx) {
		int count = sortedByWidth(font).size();
		return count == 0 ? 0 : count * (font.lineHeight + LINE_GAP) - LINE_GAP;
	}

	@Override
	public void render(Renderer2D renderer, Font font, int x, int y, HudContext ctx) {
		List<Module> modules = sortedByWidth(font);
		int maxWidth = modules.stream().mapToInt(m -> font.width(m.name())).max().orElse(0);
		int lineY = y;
		for (Module module : modules) {
			double alpha = alphas.get(module).get();
			String name = module.name();
			int lineX = x + (maxWidth - font.width(name));
			renderer.text(font, name, lineX, lineY, ColorUtil.scaleAlpha(color(), alpha));
			lineY += font.lineHeight + LINE_GAP;
		}
	}
}

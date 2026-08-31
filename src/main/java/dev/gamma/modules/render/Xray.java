package dev.gamma.modules.render;

import dev.gamma.config.setting.BlockListSetting;
import dev.gamma.config.setting.EnumSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Culling-based Xray: while enabled, {@link dev.gamma.mixin.render.RenderSectionRegionMixin}
 * substitutes air for any opaque block not on the allow-list at the point the chunk mesh is
 * built, so vanilla's own occlusion culling opens the world up around the remaining blocks —
 * no custom render pipeline needed (see the project's RenderType lockdown). If a third-party
 * chunk renderer bypasses {@code RenderSectionRegion} entirely, this module simply won't affect
 * it — a known limitation, not silently broken behavior.
 *
 * <p>The mixin only ever runs when a section's mesh actually gets rebuilt, which otherwise only
 * happens for sections an unrelated block update touches -- so without forcing a rebuild here,
 * toggling the module did nothing to already-built meshes until something else happened to
 * dirty them, and most loaded chunks never got touched at all. onEnable/onDisable mark every
 * loaded section dirty via {@code ClientLevel.setSectionRangeDirty} -- the same lightweight
 * per-section mechanism a normal block update uses (confirmed safe: that's exactly what made a
 * broken block visible again before this existed), rather than
 * {@code LevelRenderer.invalidateCompiledGeometry()}'s full discard-and-rebuild of the entire
 * ViewArea/SectionRenderDispatcher. The heavier call worked for turning Xray on but left chunks
 * that had been toggled on then off stuck blank even after re-enabling -- likely background
 * compile work from the first ViewArea landing after it had already been discarded for a second
 * one. Marking sections dirty in place sidesteps discarding that state at all.
 *
 * <p>Recompiling alone isn't enough, though: vanilla's occlusion graph only ever compiles a
 * section once it has an open-face path reachable from the camera through already-compiled
 * neighboring sections. Standing on solid ground blocks that path immediately, so it never even
 * attempts the sections below until something (digging) opens a route for it to follow -- and it
 * drops back out of the reachable set the moment the camera leaves that route, which is why
 * revealed blocks disappear again on stepping back. {@code Minecraft.smartCull} is vanilla's own
 * public toggle for this exact "smart"/reachability-based culling (flippable in-game via a debug
 * key); Xray disables it for the same reason a real xray needs to, and restores whatever it was
 * on disable.
 */
public final class Xray extends Module {

	public static volatile Xray instance;

	private final BlockListSetting blocks = register(new BlockListSetting("Blocks", "Blocks that stay visible.", List.of(
			Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.ANCIENT_DEBRIS,
			Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
			Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
			Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE)));
	private final EnumSetting<Mode> mode = register(new EnumSetting<>("Mode", "Full hide replaces culled blocks with air; opacity mode swaps them for glass instead.", Mode.class, Mode.FULL_HIDE));

	private Boolean previousSmartCull;

	public Xray() {
		super("Xray", "Culls blocks not on the allow-list so what's behind them is visible.", Category.RENDER);
		instance = this;
	}

	@Override
	protected void onEnable() {
		Minecraft client = Minecraft.getInstance();
		previousSmartCull = client.smartCull;
		client.smartCull = false;
		markLoadedSectionsDirty();
	}

	@Override
	protected void onDisable() {
		if (previousSmartCull != null) {
			Minecraft.getInstance().smartCull = previousSmartCull;
			previousSmartCull = null;
		}
		markLoadedSectionsDirty();
	}

	private void markLoadedSectionsDirty() {
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = client.level;
		if (level == null || client.player == null) {
			return;
		}
		ChunkPos center = ChunkPos.containing(client.player.blockPosition());
		int radius = client.options.renderDistance().get();
		level.setSectionRangeDirty(
				center.x() - radius, level.getMinSectionY(), center.z() - radius,
				center.x() + radius, level.getMaxSectionY(), center.z() + radius);
	}

	/** Called from the mixin for every candidate block; {@code null} means "don't touch this one". */
	public Block replacementFor(Block original) {
		if (!isEnabled() || blocks.contains(original) || !original.defaultBlockState().canOcclude()) {
			return null;
		}
		return mode.get() == Mode.FULL_HIDE ? Blocks.AIR : Blocks.GLASS;
	}

	public enum Mode {
		FULL_HIDE,
		OPACITY
	}
}

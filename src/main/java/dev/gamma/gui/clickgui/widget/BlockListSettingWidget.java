package dev.gamma.gui.clickgui.widget;

import dev.gamma.config.setting.BlockListSetting;
import dev.gamma.gui.clickgui.Theme;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class BlockListSettingWidget extends AbstractListSettingWidget<Block> {

	private final BlockListSetting blockListSetting;

	public BlockListSettingWidget(BlockListSetting setting, Theme theme) {
		super(setting, theme);
		this.blockListSetting = setting;
	}

	@Override
	protected List<Block> values() {
		return blockListSetting.get();
	}

	@Override
	protected void setValues(List<Block> values) {
		blockListSetting.set(values);
	}

	@Override
	protected String displayName(Block value) {
		return BuiltInRegistries.BLOCK.getKey(value).getPath();
	}

	@Override
	protected Optional<Block> parse(String query) {
		if (query.isEmpty()) {
			return Optional.empty();
		}
		Identifier id = query.contains(":") ? Identifier.parse(query) : Identifier.withDefaultNamespace(query);
		return BuiltInRegistries.BLOCK.getOptional(id);
	}

	@Override
	protected List<Block> search(String query) {
		String needle = query.toLowerCase(Locale.ROOT);
		if (needle.isEmpty()) {
			return List.of();
		}
		List<Block> matches = new ArrayList<>();
		for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
			if (entry.getKey().identifier().getPath().toLowerCase(Locale.ROOT).contains(needle)) {
				matches.add(entry.getValue());
				if (matches.size() >= MAX_SUGGESTIONS) {
					break;
				}
			}
		}
		return matches;
	}
}

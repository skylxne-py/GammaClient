package dev.gamma.gui.clickgui.widget;

import dev.gamma.config.setting.ItemListSetting;
import dev.gamma.gui.clickgui.Theme;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ItemListSettingWidget extends AbstractListSettingWidget<Item> {

	private final ItemListSetting itemListSetting;

	public ItemListSettingWidget(ItemListSetting setting, Theme theme) {
		super(setting, theme);
		this.itemListSetting = setting;
	}

	@Override
	protected List<Item> values() {
		return itemListSetting.get();
	}

	@Override
	protected void setValues(List<Item> values) {
		itemListSetting.set(values);
	}

	@Override
	protected String displayName(Item value) {
		return BuiltInRegistries.ITEM.getKey(value).getPath();
	}

	@Override
	protected Optional<Item> parse(String query) {
		if (query.isEmpty()) {
			return Optional.empty();
		}
		Identifier id = query.contains(":") ? Identifier.parse(query) : Identifier.withDefaultNamespace(query);
		return BuiltInRegistries.ITEM.getOptional(id);
	}

	@Override
	protected List<Item> search(String query) {
		String needle = query.toLowerCase(Locale.ROOT);
		if (needle.isEmpty()) {
			return List.of();
		}
		List<Item> matches = new ArrayList<>();
		for (var entry : BuiltInRegistries.ITEM.entrySet()) {
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

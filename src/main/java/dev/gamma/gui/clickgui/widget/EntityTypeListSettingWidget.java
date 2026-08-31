package dev.gamma.gui.clickgui.widget;

import dev.gamma.config.setting.EntityTypeListSetting;
import dev.gamma.gui.clickgui.Theme;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class EntityTypeListSettingWidget extends AbstractListSettingWidget<EntityType<?>> {

	private final EntityTypeListSetting entityTypeListSetting;

	public EntityTypeListSettingWidget(EntityTypeListSetting setting, Theme theme) {
		super(setting, theme);
		this.entityTypeListSetting = setting;
	}

	@Override
	protected List<EntityType<?>> values() {
		return entityTypeListSetting.get();
	}

	@Override
	protected void setValues(List<EntityType<?>> values) {
		entityTypeListSetting.set(values);
	}

	@Override
	protected String displayName(EntityType<?> value) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(value).getPath();
	}

	@Override
	protected Optional<EntityType<?>> parse(String query) {
		if (query.isEmpty()) {
			return Optional.empty();
		}
		Identifier id = query.contains(":") ? Identifier.parse(query) : Identifier.withDefaultNamespace(query);
		return BuiltInRegistries.ENTITY_TYPE.getOptional(id);
	}

	@Override
	protected List<EntityType<?>> search(String query) {
		String needle = query.toLowerCase(Locale.ROOT);
		if (needle.isEmpty()) {
			return List.of();
		}
		List<EntityType<?>> matches = new ArrayList<>();
		for (var entry : BuiltInRegistries.ENTITY_TYPE.entrySet()) {
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

package dev.gamma.config.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class EntityTypeListSetting extends Setting<List<EntityType<?>>> {

	public EntityTypeListSetting(String name, String description, List<EntityType<?>> defaultValue, BooleanSupplier visible) {
		super(name, description, defaultValue, visible);
	}

	public EntityTypeListSetting(String name, String description, List<EntityType<?>> defaultValue) {
		super(name, description, defaultValue);
	}

	public boolean contains(EntityType<?> type) {
		return get().contains(type);
	}

	@Override
	protected List<EntityType<?>> copy(List<EntityType<?>> value) {
		return new ArrayList<>(value);
	}

	@Override
	public JsonElement toJson() {
		JsonArray array = new JsonArray();
		for (EntityType<?> type : get()) {
			array.add(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
		}
		return array;
	}

	@Override
	public void fromJson(JsonElement json) {
		List<EntityType<?>> types = new ArrayList<>();
		for (JsonElement element : json.getAsJsonArray()) {
			types.add(BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(element.getAsString())));
		}
		set(types);
	}
}

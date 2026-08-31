package dev.gamma.config.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class ItemListSetting extends Setting<List<Item>> {

	public ItemListSetting(String name, String description, List<Item> defaultValue, BooleanSupplier visible) {
		super(name, description, defaultValue, visible);
	}

	public ItemListSetting(String name, String description, List<Item> defaultValue) {
		super(name, description, defaultValue);
	}

	public boolean contains(Item item) {
		return get().contains(item);
	}

	@Override
	protected List<Item> copy(List<Item> value) {
		return new ArrayList<>(value);
	}

	@Override
	public JsonElement toJson() {
		JsonArray array = new JsonArray();
		for (Item item : get()) {
			array.add(BuiltInRegistries.ITEM.getKey(item).toString());
		}
		return array;
	}

	@Override
	public void fromJson(JsonElement json) {
		List<Item> items = new ArrayList<>();
		for (JsonElement element : json.getAsJsonArray()) {
			items.add(BuiltInRegistries.ITEM.getValue(Identifier.parse(element.getAsString())));
		}
		set(items);
	}
}

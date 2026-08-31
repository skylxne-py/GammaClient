package dev.gamma.config.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class BlockListSetting extends Setting<List<Block>> {

	public BlockListSetting(String name, String description, List<Block> defaultValue, BooleanSupplier visible) {
		super(name, description, defaultValue, visible);
	}

	public BlockListSetting(String name, String description, List<Block> defaultValue) {
		super(name, description, defaultValue);
	}

	public boolean contains(Block block) {
		return get().contains(block);
	}

	@Override
	protected List<Block> copy(List<Block> value) {
		return new ArrayList<>(value);
	}

	@Override
	public JsonElement toJson() {
		JsonArray array = new JsonArray();
		for (Block block : get()) {
			array.add(BuiltInRegistries.BLOCK.getKey(block).toString());
		}
		return array;
	}

	@Override
	public void fromJson(JsonElement json) {
		List<Block> blocks = new ArrayList<>();
		for (JsonElement element : json.getAsJsonArray()) {
			blocks.add(BuiltInRegistries.BLOCK.getValue(Identifier.parse(element.getAsString())));
		}
		set(blocks);
	}
}

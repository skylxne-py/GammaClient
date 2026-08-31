package dev.gamma.modules.misc;

import dev.gamma.config.setting.BoolSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.gui.tooltip.ClientContainerPreviewTooltip;
import dev.gamma.gui.tooltip.ClientMapPreviewTooltip;
import dev.gamma.gui.tooltip.ContainerPreviewTooltip;
import dev.gamma.gui.tooltip.MapPreviewTooltip;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Richer tooltips: a shulker box's contents drawn as the inventory grid they actually occupy, the
 * real picture for a filled map, plus the older text extras (a map's centre and scale, and a raw
 * data component dump).
 *
 * <p>Two different channels are involved, which is why this class talks to three registration
 * points. Text lines go through Fabric's {@code ItemTooltipCallback}. Images do not: they come
 * from {@code ItemStack.getTooltipImage()} returning a {@link TooltipComponent}, which
 * {@link dev.gamma.mixin.misc.ItemStackTooltipImageMixin} supplies via {@link #tooltipImageFor},
 * and which {@code ClientTooltipComponentCallback} then turns into something drawable.
 *
 * <p>All three registrations are permanent and made once, then gated on {@link #isEnabled()} at
 * call time — Fabric's event objects have no deregister, so registering per-enable would stack up
 * duplicate handlers every time the module was toggled.
 */
public final class BetterTooltips extends Module {

	public static volatile BetterTooltips instance;

	private final BoolSetting containerContents = register(new BoolSetting("ContainerContents", "Show full shulker box / bundle contents.", true));
	private final BoolSetting containerGrid = register(new BoolSetting("ContainerGrid", "Draw those contents as the inventory grid they sit in, instead of a list of names.", true));
	private final BoolSetting mapPreview = register(new BoolSetting("MapPreview", "Draw the actual map image when hovering a filled map.", true));
	private final BoolSetting mapInfo = register(new BoolSetting("MapInfo", "Also add the map's centre coordinates and scale as text.", true));
	private final BoolSetting components = register(new BoolSetting("Components", "Dump raw data components (the modern replacement for NBT).", false));

	private boolean installed;

	public BetterTooltips() {
		super("BetterTooltips", "Shulker contents as a grid, map previews, and raw data components in tooltips.", Category.MISC);
		instance = this;
	}

	@Override
	protected void onEnable() {
		if (installed) {
			return;
		}
		installed = true;
		ItemTooltipCallback.EVENT.register(this::onTooltip);
		ClientTooltipComponentCallback.EVENT.register(data -> switch (data) {
			case ContainerPreviewTooltip container -> new ClientContainerPreviewTooltip(container);
			case MapPreviewTooltip map -> new ClientMapPreviewTooltip(map);
			// Null means "not mine" — the event chain moves on to the next handler.
			default -> null;
		});
	}

	/**
	 * The tooltip image this module wants for {@code stack}, or {@code null} to leave vanilla's
	 * answer alone.
	 *
	 * <p>{@code vanilla} being present always wins. Bundles already produce their own image
	 * tooltip, and replacing it would trade a purpose-built vanilla renderer (with its fullness
	 * bar) for a worse copy.
	 */
	public Optional<TooltipComponent> tooltipImageFor(ItemStack stack, Optional<TooltipComponent> vanilla) {
		if (!isEnabled() || vanilla.isPresent()) {
			return null;
		}
		if (containerContents.get() && containerGrid.get()) {
			ItemContainerContents container = stack.get(DataComponents.CONTAINER);
			if (container != null) {
				// allItemsCopyStream, not nonEmptyItemCopyStream: the grid wants the empty slots
				// too, so items stay in the positions they occupy in the real container.
				List<ItemStack> items = new ArrayList<>();
				container.allItemsCopyStream().forEach(items::add);
				if (!items.isEmpty()) {
					return Optional.of(new ContainerPreviewTooltip(List.copyOf(items)));
				}
			}
		}
		if (mapPreview.get()) {
			MapId mapId = stack.get(DataComponents.MAP_ID);
			if (mapId != null) {
				return Optional.of(new MapPreviewTooltip(mapId));
			}
		}
		return null;
	}

	private void onTooltip(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context, net.minecraft.world.item.TooltipFlag flag, List<Component> lines) {
		if (!isEnabled()) {
			return;
		}
		// Only fall back to the text list when the grid isn't drawing them, so contents never
		// appear twice.
		if (containerContents.get() && !containerGrid.get()) {
			ItemContainerContents container = stack.get(DataComponents.CONTAINER);
			if (container != null) {
				container.nonEmptyItemCopyStream().forEach(item -> lines.add(entryLine(item)));
			}
			BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
			if (bundle != null) {
				bundle.itemCopyStream().forEach(item -> lines.add(entryLine(item)));
			}
		}
		if (mapInfo.get()) {
			MapId mapId = stack.get(DataComponents.MAP_ID);
			if (mapId != null) {
				addMapLine(mapId, lines);
			}
		}
		if (components.get()) {
			for (TypedDataComponent<?> component : stack.getComponents()) {
				lines.add(Component.literal("§8" + idOf(component.type()) + ": " + component.value()));
			}
		}
	}

	private static Component entryLine(ItemStack item) {
		return Component.literal(ChatFormatting.GRAY + "- " + item.getCount() + "x " + item.getHoverName().getString());
	}

	private static void addMapLine(MapId mapId, List<Component> lines) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return;
		}
		MapItemSavedData data = client.level.getMapData(mapId);
		if (data == null) {
			return;
		}
		lines.add(Component.literal(String.format("%sMap center (%d, %d), scale 1:%d",
				ChatFormatting.GRAY, data.centerX, data.centerZ, 1 << data.scale)));
	}

	private static String idOf(DataComponentType<?> type) {
		var key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
		return key != null ? key.toString() : type.toString();
	}
}

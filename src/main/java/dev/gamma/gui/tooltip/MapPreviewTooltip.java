package dev.gamma.gui.tooltip;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.level.saveddata.maps.MapId;

/**
 * Data half of the filled-map tooltip preview. Carries only the id — the saved data is looked up
 * at draw time, because a map you are actively holding keeps receiving updates and a snapshot
 * taken when the tooltip was built would show a stale image.
 */
public record MapPreviewTooltip(MapId mapId) implements TooltipComponent {
}

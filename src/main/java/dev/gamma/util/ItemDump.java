package dev.gamma.util;

import dev.gamma.Gamma;
import dev.gamma.core.GammaExecutor;
import dev.gamma.core.GammaPaths;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic dump of every data component on a stack, for reverse-engineering server-side custom
 * items whose behaviour isn't described by any vanilla component.
 *
 * <p>Appends rather than truncates, and stamps each dump with wall-clock time plus
 * {@code System.nanoTime()}: the point of this tool is diffing two dumps of the <em>same</em> stack
 * taken seconds apart, which is what reveals which field is counting down and how fast. A single
 * dump can't distinguish an absolute expiry timestamp from a remaining-ticks counter.
 *
 * <p>The stack is read and formatted on the calling (client) thread — component values are game
 * state and must not be touched off-thread — and only the finished String is handed to
 * {@link GammaExecutor} for the actual file write.
 */
public final class ItemDump {

	/** Chat is unreadable past this; the file always gets the untruncated value. */
	private static final int CHAT_VALUE_LIMIT = 90;

	private ItemDump() {
	}

	public record Result(String itemId, int count, String hoverName, List<String> chatLines, Path file) {
	}

	public static Result dump(ItemStack stack, String label) {
		String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		String hoverName = stack.getHoverName().getString();

		StringBuilder file = new StringBuilder();
		file.append("=== ").append(label).append(" @ ").append(Instant.now())
				.append(" (nanoTime=").append(System.nanoTime()).append(")\n");
		file.append("item: ").append(itemId).append(" x").append(stack.getCount()).append('\n');
		file.append("hoverName: ").append(hoverName).append('\n');

		List<String> chatLines = new ArrayList<>();
		for (TypedDataComponent<?> component : stack.getComponents()) {
			String id = componentId(component);
			String value = String.valueOf(component.value());
			file.append("  ").append(id).append(" = ").append(value).append('\n');
			chatLines.add(id + " = " + truncate(value));
		}
		file.append('\n');

		Path path = GammaPaths.dir().resolve("itemdump.txt");
		String text = file.toString();
		GammaExecutor.execute(() -> {
			try {
				Files.writeString(path, text, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (IOException e) {
				Gamma.LOGGER.error("Failed to append item dump to {}", path, e);
			}
		});
		return new Result(itemId, stack.getCount(), hoverName, chatLines, path);
	}

	private static String componentId(TypedDataComponent<?> component) {
		var key = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type());
		return key != null ? key.toString() : component.type().toString();
	}

	private static String truncate(String value) {
		String flat = value.replace('\n', ' ');
		return flat.length() <= CHAT_VALUE_LIMIT ? flat : flat.substring(0, CHAT_VALUE_LIMIT) + "...";
	}
}

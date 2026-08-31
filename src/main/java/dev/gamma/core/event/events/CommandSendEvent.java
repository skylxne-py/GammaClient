package dev.gamma.core.event.events;

import dev.gamma.core.event.GammaEvent;

/**
 * Fired after the client sends a slash command to the server, e.g. typing {@code /rtp}.
 *
 * <p>{@code command} has no leading slash and is the raw text as typed, arguments included.
 * Observation only — this rides Fabric's non-cancelling {@code ClientSendMessageEvents.COMMAND},
 * so a handler cannot suppress or rewrite what was sent. That matches the packet-observation
 * boundary the rest of the codebase keeps to.
 */
public record CommandSendEvent(String command) implements GammaEvent {

	/** First whitespace-delimited token, lowercased — the command name without its arguments. */
	public String name() {
		int space = command.indexOf(' ');
		return (space < 0 ? command : command.substring(0, space)).toLowerCase(java.util.Locale.ROOT);
	}
}

package dev.gamma.util;

import dev.gamma.gui.hud.Notifications;

/**
 * The visual half of {@link SoundNotifier}: a rate-limited handle a module holds so a find can be
 * seen as well as heard.
 *
 * <p>The drawing lives in {@link Notifications} — large red text above the hotbar, not a corner
 * toast; see that class for why. This is only the per-module valve in front of it, which is where
 * the rate limit belongs: the limit is a property of how often one particular module fires, and the
 * overlay has no idea which module a message came from.
 *
 * <p>The limit exists for the same reason it does on the sound: the sources fire in bursts. A chunk
 * scan finding nine stashes as you fly over a base should say so once, not nine times, and extra
 * triggers inside the window are dropped rather than queued — a find you have already been shown is
 * not worth showing again.
 *
 * <p>Safe from any thread; {@link Notifications} takes the message on whatever thread calls and does
 * every rendering decision later, on the render thread.
 */
public final class ToastNotifier {

	private final long minIntervalMillis;

	private volatile long lastShownMillis;

	public ToastNotifier(long minIntervalMillis) {
		this.minIntervalMillis = minIntervalMillis;
	}

	/** Shows a notification unless one from this notifier was shown less than the interval ago. */
	public void show(String title, String message) {
		long now = System.currentTimeMillis();
		if (now - lastShownMillis < minIntervalMillis) {
			return;
		}
		lastShownMillis = now;
		Notifications.push(title, message);
	}

	/** Clears the rate-limit window, so the next {@link #show} is guaranteed to appear. */
	public void reset() {
		lastShownMillis = 0;
	}
}

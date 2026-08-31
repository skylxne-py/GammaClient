package dev.gamma.modules.misc.spotify;

/**
 * The immutable snapshot the overlay draws from, swapped in whole by the worker thread and only
 * ever read by the render thread — the same extraction/render split the world modules use, for
 * the same reason: no half-updated state can be observed mid-frame.
 *
 * @param status       what the overlay should be showing at all
 * @param message      one-line explanation for the non-playing statuses; ignored for {@link Status#PLAYING}
 * @param track        the current track, {@code null} unless {@code status} is {@link Status#PLAYING}
 * @param playing      whether it is actually rolling, as opposed to paused on a track
 * @param progressMs   playback position at {@code sampledAtNanos}
 * @param sampledAtNanos {@code System.nanoTime()} when the poll that produced this returned
 */
public record SpotifyState(
		Status status,
		String message,
		SpotifyTrack track,
		boolean playing,
		long progressMs,
		long sampledAtNanos) {

	public enum Status {
		/** No client id, or never logged in. */
		DISCONNECTED,
		/** Browser handed off, waiting on the callback. */
		CONNECTING,
		/** Authenticated, but Spotify reports nothing playing on any device. */
		IDLE,
		/** Authenticated and there is a track. */
		PLAYING,
		/** Authenticated but the last call failed — {@code message} says how. */
		ERROR
	}

	public static SpotifyState disconnected(String message) {
		return new SpotifyState(Status.DISCONNECTED, message, null, false, 0, System.nanoTime());
	}

	public static SpotifyState connecting() {
		return new SpotifyState(Status.CONNECTING, "Waiting for Spotify in your browser...", null, false, 0, System.nanoTime());
	}

	public static SpotifyState idle() {
		return new SpotifyState(Status.IDLE, "Nothing playing", null, false, 0, System.nanoTime());
	}

	public static SpotifyState error(String message) {
		return new SpotifyState(Status.ERROR, message, null, false, 0, System.nanoTime());
	}

	public static SpotifyState playing(SpotifyTrack track, boolean playing, long progressMs) {
		return new SpotifyState(Status.PLAYING, "", track, playing, progressMs, System.nanoTime());
	}

	/** Same state with playback flipped, so the play/pause icon responds on click rather than up to a poll later. */
	public SpotifyState withPlaying(boolean newPlaying) {
		return new SpotifyState(status, message, track, newPlaying, progressMs, sampledAtNanos);
	}

	/**
	 * Where the playhead is <em>now</em>, not where it was when the poll returned.
	 *
	 * <p>Polling often enough for a progress bar to look continuous would mean several requests a
	 * second against a rate-limited API for information the client can work out for itself: while
	 * playing, the position advances in real time. So the poll sets the reference point and this
	 * runs the clock forward from it locally, which keeps the bar smooth at a poll interval of
	 * seconds. It is clamped to the track length so a poll arriving late over a track change can't
	 * push the bar past the end.
	 */
	public long interpolatedProgressMs(long nowNanos) {
		if (!playing || track == null) {
			return progressMs;
		}
		long elapsedMs = (nowNanos - sampledAtNanos) / 1_000_000L;
		return Math.max(0, Math.min(track.durationMs(), progressMs + elapsedMs));
	}
}

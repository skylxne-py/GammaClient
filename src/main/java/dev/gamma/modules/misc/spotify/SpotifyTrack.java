package dev.gamma.modules.misc.spotify;

/**
 * One track as the overlay needs it. Deliberately not a mirror of Spotify's own JSON shape —
 * everything the HUD can't draw is dropped at the parse boundary, so the render path never
 * carries a {@code JsonObject} around or re-parses per frame.
 *
 * @param id       Spotify track id, used for the save/unsave calls and to tell "same song still
 *                 playing" from "the song changed"
 * @param title    track name
 * @param artist   artists joined with ", " — Spotify returns a list and features are common
 * @param artUrl   album cover URL, or {@code null} if the track has no images (local files don't)
 * @param durationMs total track length
 */
public record SpotifyTrack(String id, String title, String artist, String artUrl, long durationMs) {
}

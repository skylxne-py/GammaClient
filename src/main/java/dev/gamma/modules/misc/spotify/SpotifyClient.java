package dev.gamma.modules.misc.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.gamma.Gamma;
import dev.gamma.core.GammaExecutor;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Everything the overlay needs from the Spotify Web API: one poll that produces a
 * {@link SpotifyState}, and the four transport/library commands the buttons issue.
 *
 * <h2>Threading</h2>
 *
 * <p>Every method that talks to Spotify is posted to {@link GammaExecutor} and blocks there. The
 * render thread only ever reads {@link #state()}, which is a volatile reference to an immutable
 * record — the same extraction/render split the world modules use, and for the same reason: a
 * frame can't observe a half-written update.
 *
 * <p>{@link #requestPoll} drops the request if one is already in flight. Without that, a slow or
 * hanging request would let ticks pile requests up behind it and then deliver a burst of stale
 * answers in arbitrary order.
 *
 * <h2>Why the buttons update state optimistically</h2>
 *
 * <p>The control endpoints answer {@code 204 No Content}: a success tells you it was accepted, not
 * what the player looks like now, and re-polling immediately tends to race Spotify's own state
 * catching up. So a successful command flips the local flag it is known to have changed, and the
 * next scheduled poll is the authority. A failed command leaves state alone and reports why.
 */
public final class SpotifyClient {

	private static final String API = "https://api.spotify.com/v1";
	private static final Duration TIMEOUT = Duration.ofSeconds(15);

	/** What a button press asks for. */
	public enum Command {
		PLAY_PAUSE,
		NEXT,
		PREVIOUS
	}

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private final SpotifyAuth auth = new SpotifyAuth(http);
	private final AtomicBoolean pollInFlight = new AtomicBoolean();

	private volatile SpotifyState state = SpotifyState.disconnected("Not connected");
	private volatile String clientId = "";
	/** Name fragment of the device the play button should start on when nothing is active. */
	private volatile String preferredDevice = "";
	/** Set while rate-limited; polls before this are skipped rather than spent on a 429. */
	private volatile long backoffUntilMillis;

	public SpotifyState state() {
		return state;
	}

	public void setClientId(String clientId) {
		String trimmed = clientId == null ? "" : clientId.trim();
		if (trimmed.equals(this.clientId)) {
			return;
		}
		this.clientId = trimmed;
		if (!trimmed.isEmpty()) {
			// Written straight through rather than waiting for a config save, because the per-server
			// profile is not where this belongs -- see SpotifyAuth.storedClientId.
			GammaExecutor.execute(() -> auth.rememberClientId(trimmed));
		}
	}

	/** The client id remembered from a previous session, or empty. */
	public String storedClientId() {
		return auth.storedClientId();
	}

	public void setPreferredDevice(String preferredDevice) {
		this.preferredDevice = preferredDevice == null ? "" : preferredDevice.trim();
	}

	public boolean isConnected() {
		return auth.hasRefreshToken();
	}

	/**
	 * Picks up a token stored by a previous session, so connecting is a once-ever step.
	 *
	 * <p>Deliberately does not poll: this runs at startup, before per-server config has loaded, so
	 * the client id is very likely still blank and a refresh attempted now would fail and leave a
	 * misleading error on screen. The module's own tick polls once it is enabled, by which point
	 * the id is real.
	 */
	public void restoreSession(Runnable afterLoad) {
		GammaExecutor.execute(() -> {
			auth.loadStoredToken();
			if (auth.hasRefreshToken()) {
				state = SpotifyState.idle();
			}
			Minecraft.getInstance().execute(afterLoad);
		});
	}

	public void login(int port, Consumer<String> feedback) {
		String id = clientId;
		if (id.isEmpty()) {
			feedback.accept("Set ClientId first - create an app at developer.spotify.com and register "
					+ SpotifyAuth.redirectUri(port) + " as its redirect URI");
			return;
		}
		state = SpotifyState.connecting();
		feedback.accept("Opening Spotify in your browser...");
		GammaExecutor.execute(() -> auth.beginLogin(id, port, failure -> {
			if (failure == null) {
				state = SpotifyState.idle();
				feedback.accept("Connected to Spotify");
				poll();
			} else {
				state = SpotifyState.disconnected(failure);
				feedback.accept(failure);
			}
		}));
	}

	public void logout() {
		GammaExecutor.execute(() -> {
			auth.logout();
			state = SpotifyState.disconnected("Not connected");
		});
	}

	/** Cancels a browser hand-off that was never completed, freeing the callback port. */
	public void cancelPendingLogin() {
		GammaExecutor.execute(auth::abandonPendingLogin);
	}

	/** Asks for a refresh; a no-op while one is outstanding or while rate-limited. */
	public void requestPoll() {
		if (!auth.hasRefreshToken() || System.currentTimeMillis() < backoffUntilMillis) {
			return;
		}
		if (!pollInFlight.compareAndSet(false, true)) {
			return;
		}
		GammaExecutor.execute(() -> {
			try {
				poll();
			} finally {
				pollInFlight.set(false);
			}
		});
	}

	public void requestCommand(Command command, Consumer<String> feedback) {
		if (!auth.hasRefreshToken()) {
			feedback.accept("Not connected to Spotify");
			return;
		}
		GammaExecutor.execute(() -> {
			String failure = runCommand(command);
			if (failure != null) {
				feedback.accept(failure);
			}
		});
	}

	// -- the actual calls ----------------------------------------------------

	private void poll() {
		try {
			HttpResponse<String> response = send(request("/me/player").GET());
			if (response.statusCode() == 204 || response.body() == null || response.body().isBlank()) {
				// 204 is Spotify's "no active device", not an error: nothing is playing anywhere.
				state = SpotifyState.idle();
				return;
			}
			if (response.statusCode() != 200) {
				state = SpotifyState.error(describe(response));
				return;
			}
			JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
			JsonObject item = optObject(root, "item");
			if (item == null) {
				// A player exists but is on something with no track object (an ad, or a podcast
				// episode without the extra scope). Nothing to draw, and not a failure.
				state = SpotifyState.idle();
				return;
			}
			SpotifyTrack track = parseTrack(item);
			boolean playing = root.has("is_playing") && root.get("is_playing").getAsBoolean();
			long progress = root.has("progress_ms") && !root.get("progress_ms").isJsonNull()
					? root.get("progress_ms").getAsLong()
					: 0L;
			state = SpotifyState.playing(track, playing, progress);
		} catch (SpotifyAuth.SpotifyException e) {
			state = SpotifyState.disconnected(e.getMessage());
		} catch (IOException e) {
			state = SpotifyState.error("Could not reach Spotify: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (RuntimeException e) {
			Gamma.LOGGER.error("Could not read the Spotify playback response", e);
			state = SpotifyState.error("Spotify sent a response this build could not read");
		}
	}

	/** @return null on success, else a reason fit to show the user. */
	private String runCommand(Command command) {
		SpotifyState current = state;
		String missingScope = missingScopeFor(command);
		if (missingScope != null) {
			return missingScope;
		}
		try {
			HttpResponse<String> response = switch (command) {
				case PLAY_PAUSE -> current.playing()
						? send(request("/me/player/pause").method("PUT", HttpRequest.BodyPublishers.noBody()))
						: startPlayback();
				case NEXT -> send(request("/me/player/next").POST(HttpRequest.BodyPublishers.noBody()));
				case PREVIOUS -> send(request("/me/player/previous").POST(HttpRequest.BodyPublishers.noBody()));
			};
			if (response.statusCode() / 100 != 2) {
				return describe(response);
			}
			// See the class doc: 204 says "accepted", not "here is the new state".
			state = switch (command) {
				case PLAY_PAUSE -> current.withPlaying(!current.playing());
				case NEXT, PREVIOUS -> current;
			};
			if (command == Command.NEXT || command == Command.PREVIOUS) {
				requestPoll();
			}
			return null;
		} catch (SpotifyAuth.SpotifyException e) {
			return e.getMessage();
		} catch (IOException e) {
			return "Could not reach Spotify: " + e.getMessage();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	/**
	 * Names a scope this command needs that the connection is known not to have, or {@code null}.
	 *
	 * <p>Worth checking up front because the failure is otherwise indistinguishable from any other
	 * 403 and has a completely different fix: a refresh token permanently carries the scopes it was
	 * granted with, so refreshing will never acquire a missing one and the only way out is running
	 * the login again. Saying that outright beats "Forbidden".
	 */
	private String missingScopeFor(Command command) {
		String needed = SpotifyAuth.SCOPE_MODIFY_PLAYBACK;
		return auth.hasScope(needed) ? null : missingScopeMessage(needed);
	}

	private static String missingScopeMessage(String scope) {
		return "Your Spotify connection was granted without the '" + scope + "' permission. "
				+ "Run '.spotify connect' again and approve it - refreshing can't add one.";
	}

	// -- devices -------------------------------------------------------------

	/**
	 * Starts playback, transferring to a device first when nothing is active.
	 *
	 * <p>Spotify has no concept of "just play somewhere" — a bare play call against no active
	 * device is a 404, which is what "No active Spotify device" was. Since the device list is one
	 * request away and the answer is nearly always "the desktop app", the play button now resolves
	 * that itself instead of sending you to alt-tab and press play in Spotify.
	 */
	private HttpResponse<String> startPlayback() throws SpotifyAuth.SpotifyException, IOException, InterruptedException {
		HttpResponse<String> response = send(request("/me/player/play").method("PUT", HttpRequest.BodyPublishers.noBody()));
		if (response.statusCode() != 404) {
			return response;
		}
		List<Device> playable = devices().playable();
		if (playable.isEmpty()) {
			return response;
		}
		// Preference order: the device you named, then whatever is already active, then anything.
		// Without the first of those, "first available" is whatever order Spotify happened to
		// return, which is how a play press ends up starting music on a speaker in another room.
		String preferred = preferredDevice;
		Device target = null;
		if (!preferred.isEmpty()) {
			String wanted = preferred.toLowerCase(Locale.ROOT);
			target = playable.stream()
					.filter(device -> device.name().toLowerCase(Locale.ROOT).contains(wanted))
					.findFirst()
					.orElse(null);
		}
		if (target == null) {
			target = playable.stream().filter(Device::active).findFirst().orElse(playable.getFirst());
		}
		return transferTo(target.id(), true);
	}

	/**
	 * Every device Spotify will play on, with the status that produced the answer.
	 *
	 * <p>The status is carried rather than collapsed because "the API said zero devices" and "the
	 * API refused the question" are completely different problems with the same shape, and folding
	 * both into an empty list is how "no devices available" ended up being reported for a call that
	 * might never have succeeded.
	 *
	 * <p>Devices with a null {@code id} are kept and marked unplayable rather than dropped. Spotify
	 * returns those for restricted endpoints (some Connect speakers, some web players); they cannot
	 * be transferred to, but a user looking for their PC in the list needs to see that it is there
	 * and why it can't be used, not an empty list.
	 */
	public DeviceList devices() throws SpotifyAuth.SpotifyException, IOException, InterruptedException {
		HttpResponse<String> response = send(request("/me/player/devices").GET());
		if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
			return new DeviceList(response.statusCode(), List.of(), reasonOf(response.body()));
		}
		JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
		if (!root.has("devices")) {
			return new DeviceList(200, List.of(), null);
		}
		List<Device> devices = new ArrayList<>();
		for (JsonElement element : root.getAsJsonArray("devices")) {
			JsonObject device = element.getAsJsonObject();
			devices.add(new Device(
					device.has("id") && !device.get("id").isJsonNull() ? device.get("id").getAsString() : null,
					device.has("name") ? device.get("name").getAsString() : "Unknown",
					device.has("type") ? device.get("type").getAsString() : "",
					device.has("is_active") && device.get("is_active").getAsBoolean(),
					device.has("is_restricted") && device.get("is_restricted").getAsBoolean()));
		}
		return new DeviceList(200, devices, null);
	}

	/** The device query's outcome, including the status so a refusal isn't mistaken for an empty list. */
	public record DeviceList(int status, List<Device> all, String error) {

		public boolean ok() {
			return status == 200;
		}

		/** Those that can actually be transferred to — Spotify gives restricted devices no id. */
		public List<Device> playable() {
			return all.stream().filter(device -> device.id() != null && !device.restricted()).toList();
		}
	}

	private HttpResponse<String> transferTo(String deviceId, boolean play) throws SpotifyAuth.SpotifyException, IOException, InterruptedException {
		String body = "{\"device_ids\":[\"" + deviceId + "\"],\"play\":" + play + "}";
		return send(request("/me/player").header("Content-Type", "application/json")
				.method("PUT", HttpRequest.BodyPublishers.ofString(body)));
	}

	/** Lists devices and reports them through {@code feedback}. */
	public void requestDevices(Consumer<String> feedback) {
		GammaExecutor.execute(() -> {
			try {
				DeviceList devices = devices();
				if (!devices.ok()) {
					feedback.accept("Device list failed: HTTP " + devices.status()
							+ (devices.error() == null ? "" : " - " + devices.error()));
					return;
				}
				if (devices.all().isEmpty()) {
					feedback.accept("Spotify reports no devices at all. A device only exists while a "
							+ "Spotify app is open and logged in to the same account you connected here - "
							+ "run '.spotify diagnose' to see which account that is.");
					return;
				}
				for (Device device : devices.all()) {
					feedback.accept((device.active() ? "* " : "  ") + device.name()
							+ (device.type().isEmpty() ? "" : " (" + device.type() + ")")
							+ (device.id() == null || device.restricted() ? " [restricted - cannot be controlled]" : ""));
				}
			} catch (SpotifyAuth.SpotifyException e) {
				feedback.accept(e.getMessage());
			} catch (IOException e) {
				feedback.accept("Could not reach Spotify: " + e.getMessage());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	/** Moves playback to the first device whose name contains {@code nameFragment}, and starts it. */
	public void requestTransfer(String nameFragment, Consumer<String> feedback) {
		GammaExecutor.execute(() -> {
			try {
				String wanted = nameFragment.toLowerCase(Locale.ROOT);
				Device target = devices().playable().stream()
						.filter(device -> device.name().toLowerCase(Locale.ROOT).contains(wanted))
						.findFirst()
						.orElse(null);
				if (target == null) {
					feedback.accept("No controllable Spotify device matching '" + nameFragment + "'");
					return;
				}
				HttpResponse<String> response = transferTo(target.id(), true);
				feedback.accept(response.statusCode() / 100 == 2
						? "Playing on " + target.name()
						: describe(response));
				requestPoll();
			} catch (SpotifyAuth.SpotifyException e) {
				feedback.accept(e.getMessage());
			} catch (IOException e) {
				feedback.accept("Could not reach Spotify: " + e.getMessage());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	/** One thing Spotify can play on. A null {@code id} means Spotify won't let it be controlled. */
	public record Device(String id, String name, String type, boolean active, boolean restricted) {
	}

	/** For {@code .spotify status} — what the current connection is actually allowed to do. */
	public String grantedScopeSummary() {
		Set<String> granted = auth.grantedScopes();
		return granted.isEmpty() ? "not known yet (no token refresh so far)" : String.join(", ", new TreeSet<>(granted));
	}

	// -- diagnostics ---------------------------------------------------------

	/**
	 * Runs each call the overlay depends on and reports the raw status of every one.
	 *
	 * <p>This exists because the failures being chased were mutually contradictory — a device list
	 * that comes back empty while a track is playing is impossible, since a playing track implies
	 * an active device — and that only happens when something is being reported as an empty result
	 * that was really an error. Rather than keep guessing at which, every probe here prints the
	 * status code and Spotify's own message, and none of them interpret anything.
	 *
	 * <p>The account identity is the first probe on purpose. Authorising as one account while the
	 * Spotify app on the PC is signed in to another produces exactly this pattern: reads succeed,
	 * the device list is empty because that account has no open client, and library writes go to an
	 * account you aren't looking at. The names printed here make that visible in one line.
	 */
	public void requestDiagnostics(Consumer<String> feedback) {
		GammaExecutor.execute(() -> {
			feedback.accept("--- Spotify diagnostics ---");
			feedback.accept("Permissions granted: " + grantedScopeSummary());
			try {
				probeAccount(feedback);
				probePlayer(feedback);
				probeDevices(feedback);
			} catch (SpotifyAuth.SpotifyException e) {
				feedback.accept("Could not get a token: " + e.getMessage());
			} catch (IOException e) {
				feedback.accept("Could not reach Spotify: " + e.getMessage());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	private void probeAccount(Consumer<String> feedback) throws SpotifyAuth.SpotifyException, IOException, InterruptedException {
		HttpResponse<String> response = send(request("/me").GET());
		if (response.statusCode() != 200) {
			feedback.accept("Account: HTTP " + response.statusCode() + describeSuffix(response));
			return;
		}
		JsonObject me = JsonParser.parseString(response.body()).getAsJsonObject();
		String product = me.has("product") && !me.get("product").isJsonNull() ? me.get("product").getAsString() : "unknown";
		feedback.accept("Account: " + textOf(me, "display_name") + " (" + textOf(me, "id") + ")"
				+ ", plan: " + product + ", country: " + textOf(me, "country"));
		if ("unknown".equals(product)) {
			feedback.accept("  (plan needs the user-read-private permission - reconnect to see it)");
		} else if (!"premium".equals(product)) {
			feedback.accept("  Playback control (prev/play/next, device transfer) is Premium-only, "
					+ "so it will keep failing on this plan. Saving tracks is not, and should work.");
		}
	}

	private void probePlayer(Consumer<String> feedback) throws SpotifyAuth.SpotifyException, IOException, InterruptedException {
		HttpResponse<String> response = send(request("/me/player").GET());
		if (response.statusCode() == 204) {
			feedback.accept("Player: HTTP 204 - nothing playing on any device right now");
			return;
		}
		if (response.statusCode() != 200) {
			feedback.accept("Player: HTTP " + response.statusCode() + describeSuffix(response));
			return;
		}
		JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
		JsonObject device = optObject(root, "device");
		JsonObject item = optObject(root, "item");
		feedback.accept("Player: HTTP 200, playing on "
				+ (device == null ? "an unnamed device" : textOf(device, "name"))
				+ ", track: " + (item == null ? "none" : textOf(item, "name")));
	}

	private void probeDevices(Consumer<String> feedback) throws SpotifyAuth.SpotifyException, IOException, InterruptedException {
		DeviceList devices = devices();
		if (!devices.ok()) {
			feedback.accept("Devices: HTTP " + devices.status() + (devices.error() == null ? "" : " - " + devices.error()));
			return;
		}
		feedback.accept("Devices: HTTP 200, " + devices.all().size() + " reported");
		for (Device device : devices.all()) {
			feedback.accept("  " + (device.active() ? "* " : "- ") + device.name() + " (" + device.type() + ")"
					+ (device.id() == null || device.restricted() ? " restricted" : ""));
		}
	}

	private static String truncate(String body) {
		if (body == null || body.isBlank()) {
			return "(empty)";
		}
		return body.length() <= 300 ? body : body.substring(0, 300) + "...";
	}

	private static String describeSuffix(HttpResponse<String> response) {
		String reason = reasonOf(response.body());
		return reason == null ? "" : " - " + reason;
	}

	private static String textOf(JsonObject object, String key) {
		return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "?";
	}

	// -- plumbing ------------------------------------------------------------

	private HttpRequest.Builder request(String path) throws SpotifyAuth.SpotifyException {
		return HttpRequest.newBuilder(URI.create(API + path))
				.header("Authorization", "Bearer " + auth.accessToken(clientId))
				.timeout(TIMEOUT);
	}

	private HttpResponse<String> send(HttpRequest.Builder builder) throws IOException, InterruptedException {
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	/**
	 * Turns a failed response into something worth showing. The three that actually happen in
	 * normal use get spelled out, because each has a different fix and the raw status code implies
	 * none of them.
	 */
	private String describe(HttpResponse<String> response) {
		int status = response.statusCode();
		if (status == 429) {
			long seconds = response.headers().firstValue("Retry-After").map(value -> {
				try {
					return Long.parseLong(value.trim());
				} catch (NumberFormatException e) {
					return 5L;
				}
			}).orElse(5L);
			backoffUntilMillis = System.currentTimeMillis() + seconds * 1000L;
			return "Spotify is rate-limiting; backing off " + seconds + "s";
		}
		if (status == 404) {
			return "No Spotify device available - open Spotify on this PC or your phone once, "
					+ "then the play button can take it from there";
		}
		if (status == 403) {
			String reason = reasonOf(response.body());
			// Spotify says two very different things with the same status. "Premium required" is
			// its own message and is final -- playback control is Premium-only, which nothing this
			// end can change. A bare "Forbidden" is what a missing scope looks like, and that one
			// is fixable by logging in again, so it must not be reported as the Premium wall.
			if (reason != null && reason.toLowerCase(Locale.ROOT).contains("premium")) {
				return "Spotify refused it: " + reason + " (playback control is Premium-only)";
			}
			return "Spotify refused it" + (reason == null ? "" : ": " + reason)
					+ ". If this is the save button or a transport control, the connection is most "
					+ "likely missing a permission - run '.spotify connect' again and approve it.";
		}
		if (status == 401) {
			return "Spotify session expired - reconnect";
		}
		String reason = reasonOf(response.body());
		return "Spotify returned HTTP " + status + (reason == null ? "" : ": " + reason);
	}

	private static String reasonOf(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			JsonObject error = optObject(JsonParser.parseString(body).getAsJsonObject(), "error");
			if (error != null && error.has("message")) {
				return error.get("message").getAsString();
			}
		} catch (RuntimeException ignored) {
			// A non-JSON error body is worth no more than its status code.
		}
		return null;
	}

	private static SpotifyTrack parseTrack(JsonObject item) {
		String id = item.has("id") && !item.get("id").isJsonNull() ? item.get("id").getAsString() : "";
		String title = item.has("name") ? item.get("name").getAsString() : "Unknown";
		long duration = item.has("duration_ms") ? item.get("duration_ms").getAsLong() : 0L;

		List<String> artists = new ArrayList<>();
		if (item.has("artists")) {
			for (JsonElement element : item.getAsJsonArray("artists")) {
				JsonObject artist = element.getAsJsonObject();
				if (artist.has("name")) {
					artists.add(artist.get("name").getAsString());
				}
			}
		}
		if (artists.isEmpty() && item.has("show")) {
			// Podcast episodes carry a show rather than artists.
			JsonObject show = item.getAsJsonObject("show");
			if (show.has("publisher")) {
				artists.add(show.get("publisher").getAsString());
			}
		}
		return new SpotifyTrack(id, title, String.join(", ", artists), pickArtUrl(item), duration);
	}

	/**
	 * Smallest cover at least {@link #MIN_ART_PIXELS} across. Spotify hands back three sizes
	 * (typically 640/300/64) largest-first; the overlay draws it at a few dozen pixels, so pulling
	 * the 640 would be several hundred KB downloaded to be thrown away, and the 64 goes soft the
	 * moment the element is scaled up in the HUD editor.
	 */
	private static String pickArtUrl(JsonObject item) {
		JsonObject album = optObject(item, "album");
		JsonArray images = album != null && album.has("images") ? album.getAsJsonArray("images") : null;
		if (images == null && item.has("images")) {
			images = item.getAsJsonArray("images");
		}
		if (images == null || images.isEmpty()) {
			return null;
		}
		String best = null;
		int bestWidth = Integer.MAX_VALUE;
		String largest = null;
		int largestWidth = -1;
		for (JsonElement element : images) {
			JsonObject image = element.getAsJsonObject();
			if (!image.has("url")) {
				continue;
			}
			String url = image.get("url").getAsString();
			int width = image.has("width") && !image.get("width").isJsonNull() ? image.get("width").getAsInt() : 0;
			if (width > largestWidth) {
				largestWidth = width;
				largest = url;
			}
			if (width >= MIN_ART_PIXELS && width < bestWidth) {
				bestWidth = width;
				best = url;
			}
		}
		return best != null ? best : largest;
	}

	private static final int MIN_ART_PIXELS = 200;

	private static JsonObject optObject(JsonObject parent, String key) {
		if (parent == null || !parent.has(key) || parent.get(key).isJsonNull() || !parent.get(key).isJsonObject()) {
			return null;
		}
		return parent.getAsJsonObject(key);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}

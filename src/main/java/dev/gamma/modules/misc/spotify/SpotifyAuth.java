package dev.gamma.modules.misc.spotify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.gamma.Gamma;
import dev.gamma.core.GammaPaths;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Spotify's Authorization Code flow with PKCE, plus the token bookkeeping that follows it.
 *
 * <h2>Why PKCE, and why your own client id</h2>
 *
 * <p>The other grant types need a client <em>secret</em>, and a secret compiled into a mod anyone
 * can download is not a secret. PKCE exists precisely for clients that cannot keep one: the client
 * proves it is the same party that started the flow by presenting the verifier whose SHA-256 it
 * committed to up front, so nothing long-lived has to ship in the jar.
 *
 * <p>The client <em>id</em> is not a secret, but it still has to be yours: a Spotify app in
 * development mode only works for accounts its owner has added to it, so a shared one would work
 * for nobody but the owner. Hence the setting. Create an app at developer.spotify.com and register
 * exactly the redirect URI {@link #redirectUri(int)} builds — Spotify matches it literally, and
 * wants the loopback <em>address</em> rather than the name {@code localhost}.
 *
 * <h2>Where the tokens live</h2>
 *
 * <p>The refresh token goes to {@code gamma/spotify.json} in plain text. That is a real credential
 * — anyone with the file can drive that account's playback until it is revoked at
 * spotify.com/account/apps — and it is stored the way desktop clients generally store one, but it
 * is worth knowing before syncing the game directory anywhere. The access token is short-lived and
 * deliberately never written to disk.
 *
 * <p>Everything here runs on {@link dev.gamma.core.GammaExecutor}: every method blocks on network
 * or disk, and none of it may touch the client thread.
 */
public final class SpotifyAuth {

	private static final String AUTHORIZE_ENDPOINT = "https://accounts.spotify.com/authorize";
	private static final String TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token";

	public static final String SCOPE_READ_PLAYBACK = "user-read-playback-state";
	public static final String SCOPE_MODIFY_PLAYBACK = "user-modify-playback-state";
	public static final String SCOPE_READ_CURRENT = "user-read-currently-playing";
	public static final String SCOPE_READ_PRIVATE = "user-read-private";

	/**
	 * Read playback to show it, modify playback for the transport buttons and device transfer, and
	 * read-private so {@code .spotify diagnose} can name the account and its plan. The library scopes
	 * were dropped with the save button: this client's Spotify app is refused those endpoints, and
	 * asking for permissions nothing uses is worse than not asking.
	 *
	 */
	private static final List<String> SCOPES = List.of(
			SCOPE_READ_PLAYBACK, SCOPE_MODIFY_PLAYBACK, SCOPE_READ_CURRENT,
			SCOPE_READ_PRIVATE);

	private static final Duration TIMEOUT = Duration.ofSeconds(15);
	/** Refresh this far before real expiry, so a request never races the boundary. */
	private static final long EXPIRY_MARGIN_MS = 60_000L;

	private final HttpClient http;

	private String refreshToken;
	private String accessToken;
	private long accessExpiresAtMillis;
	/**
	 * What Spotify actually granted, as opposed to what was asked for — the token response reports
	 * it and the two can differ (see the {@code show_dialog} comment). Empty means "not known
	 * yet", which callers treat as permissive: the first token refresh fills it in.
	 */
	private Set<String> grantedScopes = Set.of();

	private HttpServer callbackServer;
	private String pendingVerifier;
	private String pendingState;

	public SpotifyAuth(HttpClient http) {
		this.http = http;
	}

	public static String redirectUri(int port) {
		return "http://127.0.0.1:" + port + "/callback";
	}

	public synchronized boolean hasRefreshToken() {
		return refreshToken != null;
	}

	/** Every scope Spotify reported granting, or empty if no token response has been seen yet. */
	public synchronized Set<String> grantedScopes() {
		return grantedScopes;
	}

	/**
	 * Whether a scope is known to be missing. Answers {@code true} while the granted set is still
	 * unknown, so a command is never blocked on the strength of a guess — an actual 403 is a better
	 * error than a made-up one.
	 */
	public synchronized boolean hasScope(String scope) {
		return grantedScopes.isEmpty() || grantedScopes.contains(scope);
	}

	/**
	 * The client id, alongside the token, so it survives independently of any world.
	 *
	 * <p>It lives here rather than only in the module's own setting because module settings are
	 * saved per server profile, written when a world unloads and read when one loads. A client id
	 * pasted in the main menu was therefore never written anywhere, and one pasted on server A did
	 * not exist on server B -- which is not how an account credential should behave. This file is
	 * client-wide, which matches what the value actually is.
	 */
	private String storedClientId = "";

	public synchronized String storedClientId() {
		return storedClientId;
	}

	/** Remembers a client id for next launch. No-op when it has not changed. */
	public synchronized void rememberClientId(String clientId) {
		String trimmed = clientId == null ? "" : clientId.trim();
		if (trimmed.equals(storedClientId)) {
			return;
		}
		storedClientId = trimmed;
		writeToken();
	}

	/** Reads the stored refresh token and client id, if present. Call off-thread. */
	public synchronized void loadStoredToken() {
		Path file = tokenFile();
		if (!Files.isRegularFile(file)) {
			return;
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			if (root.has("refreshToken")) {
				refreshToken = root.get("refreshToken").getAsString();
			}
			if (root.has("clientId")) {
				storedClientId = root.get("clientId").getAsString();
			}
		} catch (Exception e) {
			Gamma.LOGGER.warn("Could not read {}, treating Spotify as logged out", file, e);
		}
	}

	public void logout() {
		abandonPendingLogin();
		synchronized (this) {
			refreshToken = null;
			accessToken = null;
			accessExpiresAtMillis = 0;
			grantedScopes = Set.of();
		}
		// Rewritten rather than deleted: logging out should forget the credential, not the client
		// id, which is app configuration and is a nuisance to paste back in every time.
		synchronized (this) {
			writeToken();
		}
	}

	/**
	 * Starts the browser hand-off. {@code onResult} is called off-thread with {@code null} on
	 * success, or a reason fit to show the user on failure.
	 */
	public void beginLogin(String clientId, int port, Consumer<String> onResult) {
		String verifier = randomUrlSafe(64);
		String state = randomUrlSafe(16);
		String challenge = challengeFor(verifier);
		synchronized (this) {
			stopCallbackServer();
			pendingVerifier = verifier;
			pendingState = state;
			try {
				// Bound to the loopback interface specifically, not 0.0.0.0: this exists only to
				// hear this machine's own browser finish the redirect, and nothing off the machine
				// should be able to reach an endpoint that accepts an authorization code.
				callbackServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
			} catch (IOException e) {
				pendingVerifier = null;
				pendingState = null;
				onResult.accept("Port " + port + " is in use - change CallbackPort (and the redirect URI in your Spotify app)");
				return;
			}
			callbackServer.createContext("/callback", exchange -> handleCallback(exchange, clientId, port, onResult));
			callbackServer.setExecutor(null);
			callbackServer.start();
		}

		// show_dialog=true is load-bearing, not politeness. Spotify reuses an existing grant when
		// the account has authorised this client id before, and hands back a token carrying the
		// scopes of *that* grant rather than the ones just asked for -- so an app authorised once
		// with a narrower set keeps answering 403 on everything outside it, no matter how many
		// times you reconnect. Forcing the consent screen is what makes the granted set match the
		// requested set. Scopes are joined with %20 rather than URLEncoder's + for the same
		// belt-and-braces reason: it is the separator Spotify's own documentation uses.
		String url = AUTHORIZE_ENDPOINT
				+ "?client_id=" + encode(clientId)
				+ "&response_type=code"
				+ "&redirect_uri=" + encode(redirectUri(port))
				+ "&code_challenge_method=S256"
				+ "&code_challenge=" + challenge
				+ "&state=" + state
				+ "&show_dialog=true"
				+ "&scope=" + String.join("%20", SCOPES);
		try {
			net.minecraft.util.Util.getPlatform().openUri(URI.create(url));
		} catch (Exception e) {
			Gamma.LOGGER.error("Could not open a browser for the Spotify login. Open this URL manually: {}", url, e);
			onResult.accept("Could not open a browser - the login URL is in the log");
		}
	}

	private void handleCallback(HttpExchange exchange, String clientId, int port, Consumer<String> onResult) throws IOException {
		Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
		String failure = null;
		synchronized (this) {
			if (params.containsKey("error")) {
				failure = "Spotify refused the login: " + params.get("error");
			} else if (pendingState == null || !pendingState.equals(params.get("state"))) {
				// The state parameter is the only thing tying this request back to the login this
				// process started; anything else arriving on the port is not our browser.
				failure = "Login response did not match the request that started it";
			} else if (!params.containsKey("code")) {
				failure = "Spotify sent no authorization code";
			}
		}
		respond(exchange, failure == null
				? "Gamma is connected to Spotify. You can close this tab."
				: "Gamma could not connect: " + failure);

		if (failure == null) {
			failure = exchangeCode(params.get("code"), clientId, port);
		}
		abandonPendingLogin();
		onResult.accept(failure);
	}

	/** @return null on success, else a reason. */
	private String exchangeCode(String code, String clientId, int port) {
		String verifier;
		synchronized (this) {
			verifier = pendingVerifier;
		}
		if (verifier == null) {
			return "Login expired before Spotify answered";
		}
		Map<String, String> form = new HashMap<>();
		form.put("grant_type", "authorization_code");
		form.put("code", code);
		form.put("redirect_uri", redirectUri(port));
		form.put("client_id", clientId);
		form.put("code_verifier", verifier);
		return postToken(form, true);
	}

	/**
	 * A usable access token, refreshing first if the current one is near expiry.
	 *
	 * @throws SpotifyException if there is nothing to refresh from, or the refresh failed
	 */
	public String accessToken(String clientId) throws SpotifyException {
		synchronized (this) {
			if (accessToken != null && System.currentTimeMillis() < accessExpiresAtMillis - EXPIRY_MARGIN_MS) {
				return accessToken;
			}
			if (refreshToken == null) {
				throw new SpotifyException("Not connected to Spotify");
			}
		}
		Map<String, String> form = new HashMap<>();
		form.put("grant_type", "refresh_token");
		form.put("refresh_token", refreshTokenSnapshot());
		form.put("client_id", clientId);
		String failure = postToken(form, false);
		if (failure != null) {
			throw new SpotifyException(failure);
		}
		synchronized (this) {
			return accessToken;
		}
	}

	private synchronized String refreshTokenSnapshot() {
		return refreshToken;
	}

	/**
	 * Shared body of both grant types, which differ only in their form fields and in whether a
	 * missing {@code refresh_token} in the response is a problem.
	 *
	 * @param requireRefresh true for the initial exchange, where Spotify must return one; false
	 *                       for a refresh, where it only sometimes rotates it
	 * @return null on success, else a reason
	 */
	private String postToken(Map<String, String> form, boolean requireRefresh) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_ENDPOINT))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.timeout(TIMEOUT)
				.POST(HttpRequest.BodyPublishers.ofString(formEncode(form)))
				.build();
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				return describeTokenFailure(response.statusCode(), response.body());
			}
			JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
			synchronized (this) {
				accessToken = json.get("access_token").getAsString();
				accessExpiresAtMillis = System.currentTimeMillis() + json.get("expires_in").getAsLong() * 1000L;
				if (json.has("scope") && !json.get("scope").isJsonNull()) {
					grantedScopes = Set.copyOf(Arrays.asList(json.get("scope").getAsString().split(" +")));
				}
				if (json.has("refresh_token")) {
					refreshToken = json.get("refresh_token").getAsString();
					writeToken();
				} else if (requireRefresh) {
					return "Spotify returned no refresh token";
				}
			}
			return null;
		} catch (IOException e) {
			return "Could not reach Spotify: " + e.getMessage();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "Interrupted talking to Spotify";
		} catch (RuntimeException e) {
			Gamma.LOGGER.error("Malformed token response from Spotify", e);
			return "Spotify sent a response this build could not read";
		}
	}

	private static String describeTokenFailure(int status, String body) {
		String detail = "";
		try {
			JsonObject json = JsonParser.parseString(body).getAsJsonObject();
			if (json.has("error_description")) {
				detail = ": " + json.get("error_description").getAsString();
			} else if (json.has("error")) {
				detail = ": " + json.get("error").getAsString();
			}
		} catch (RuntimeException ignored) {
			// A non-JSON error body is worth no more than its status code.
		}
		if (status == 400 || status == 401) {
			return "Spotify rejected the login" + detail + " - check ClientId and that the redirect URI is registered";
		}
		return "Spotify returned HTTP " + status + detail;
	}

	private void writeToken() {
		JsonObject root = new JsonObject();
		if (refreshToken != null) {
			root.addProperty("refreshToken", refreshToken);
		}
		if (!storedClientId.isEmpty()) {
			root.addProperty("clientId", storedClientId);
		}
		try {
			Files.writeString(tokenFile(), root.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Gamma.LOGGER.error("Could not save the Spotify refresh token; you will have to reconnect next launch", e);
		}
	}

	/** Closes the callback listener and forgets the in-flight login. Safe to call at any time. */
	public void abandonPendingLogin() {
		synchronized (this) {
			stopCallbackServer();
			pendingVerifier = null;
			pendingState = null;
		}
	}

	private synchronized void stopCallbackServer() {
		if (callbackServer != null) {
			// Zero delay: the only handler is the one that just finished, or none at all.
			callbackServer.stop(0);
			callbackServer = null;
		}
	}

	private static Path tokenFile() {
		return GammaPaths.root().resolve("spotify.json");
	}

	private static void respond(HttpExchange exchange, String message) throws IOException {
		byte[] body = ("<!doctype html><meta charset=\"utf-8\"><title>Gamma</title>"
				+ "<body style=\"background:#16171b;color:#e8e8ec;font:16px sans-serif;padding:3rem\">"
				+ escapeHtml(message) + "</body>").getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
		exchange.sendResponseHeaders(200, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	private static String escapeHtml(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static Map<String, String> parseQuery(String rawQuery) {
		Map<String, String> params = new HashMap<>();
		if (rawQuery == null) {
			return params;
		}
		for (String pair : rawQuery.split("&")) {
			int split = pair.indexOf('=');
			if (split > 0) {
				params.put(URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8),
						URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
			}
		}
		return params;
	}

	private static String formEncode(Map<String, String> form) {
		StringBuilder builder = new StringBuilder();
		for (Map.Entry<String, String> entry : form.entrySet()) {
			if (!builder.isEmpty()) {
				builder.append('&');
			}
			builder.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
		}
		return builder.toString();
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String randomUrlSafe(int bytes) {
		byte[] buffer = new byte[bytes];
		new SecureRandom().nextBytes(buffer);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
	}

	private static String challengeFor(String verifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required of every Java platform", e);
		}
	}

	/** Anything that stops a Spotify call producing a result, carrying a message fit to show a user. */
	public static final class SpotifyException extends Exception {
		public SpotifyException(String message) {
			super(message);
		}
	}
}

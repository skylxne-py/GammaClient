package dev.gamma.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.gamma.Gamma;
import dev.gamma.config.GammaSettings;

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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * Microsoft sign-in, and the four-hop token chain that turns the result into a Minecraft session.
 *
 * <h2>Two identity stacks, and why sign-in has three shapes</h2>
 *
 * <p>Microsoft runs two of everything. Legacy MSA applications — sixteen hex digits, which is what
 * {@link #DEFAULT_CLIENT_ID} is — live on {@code login.live.com}. Azure-portal registrations are
 * GUIDs on {@code login.microsoftonline.com/.../v2.0}. Endpoints, scope and redirect are all
 * derived from the shape of the configured id rather than from a setting nobody could answer; see
 * {@link #isLegacyClientId}.
 *
 * <p>The difference that decides the whole user experience is the redirect. Azure public clients
 * match {@code http://127.0.0.1} by host and ignore the port, so an ephemeral loopback listener
 * works with nothing registered — {@link #beginBrowserLogin} captures the code by itself and the
 * user types nothing. The legacy application has a fixed list of registered redirects, loopback is
 * not among them, and asking for one is refused outright:
 * {@code invalid_request: The provided value for the input parameter 'redirect_uri' is not valid}.
 * Its one usable redirect is {@link #LEGACY_REDIRECT}, a blank Microsoft page carrying the code in
 * its address bar, so {@link #manualAuthorizeUrl} asks the user to paste that address back.
 *
 * <p>{@link #supportsLoopback()} is the switch between the two. Device code is a third path, behind
 * a link, for machines that cannot open a browser at all; it exists only on the v2.0 stack, so it
 * also wants an Azure id.
 *
 * <h2>The chain</h2>
 *
 * <pre>
 *   MSA  ---- browser / device code / refresh -->  access token
 *        ---- user.auth.xboxlive.com  ---->  XBL token + user hash
 *        ---- xsts.auth.xboxlive.com  ---->  XSTS token (+ xuid)
 *        ---- api.minecraftservices.com --->  Minecraft access token
 *        ---- /minecraft/profile      ---->  name + UUID
 * </pre>
 *
 * <p>Every step blocks on the network. Nothing in this class may be called from the client or
 * render thread — see the project conventions. {@link AccountManager} is the only caller and it posts
 * all of it to {@link dev.gamma.core.GammaExecutor}.
 *
 * <h2>On the client id</h2>
 *
 * <p>{@link #DEFAULT_CLIENT_ID} is the public Minecraft launcher identifier that third-party
 * launchers use for exactly this. A client id is not a secret — it names the app requesting access,
 * and the user still approves the request in their own browser. It is compiled in, so no user ever
 * has to supply one; {@code MsaClientId} exists only as an escape hatch if Microsoft refuses it.
 *
 * <p><strong>Verified against Microsoft:</strong> that the legacy id rejects a loopback redirect,
 * which is what forced the manual path. <strong>Still unverified:</strong> everything after the
 * authorize request on the legacy stack — no live sign-in has completed. The Azure path is
 * documented behaviour throughout and is the one to prefer if a registration is acceptable.
 */
public final class MsaAuth {

	/** See the class note: public launcher id, overridable via {@code MsaClientId}. */
	public static final String DEFAULT_CLIENT_ID = "00000000402b5328";

	private static final String LIVE_AUTHORIZE = "https://login.live.com/oauth20_authorize.srf";
	private static final String LIVE_TOKEN = "https://login.live.com/oauth20_token.srf";

	private static final String V2_AUTHORIZE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
	private static final String V2_TOKEN = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
	private static final String V2_DEVICE_CODE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";

	private static final String XBL_ENDPOINT = "https://user.auth.xboxlive.com/user/authenticate";
	private static final String XSTS_ENDPOINT = "https://xsts.auth.xboxlive.com/xsts/authorize";
	private static final String MC_LOGIN_ENDPOINT = "https://api.minecraftservices.com/authentication/login_with_xbox";
	private static final String MC_PROFILE_ENDPOINT = "https://api.minecraftservices.com/minecraft/profile";

	/** {@code offline_access} is what makes the v2.0 stack hand back a refresh token at all. */
	private static final String SCOPE = "XboxLive.signin offline_access";

	/**
	 * The legacy stack predates the {@code XboxLive.signin} scope and names the same permission
	 * this way. It returns a refresh token without being asked, so there is no {@code offline_access}
	 * counterpart.
	 */
	private static final String LEGACY_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";

	/**
	 * The one redirect the Minecraft launcher application actually has registered. It is a
	 * Microsoft-hosted page that does nothing except carry {@code ?code=...} in its address bar,
	 * which is why the legacy path cannot capture the code by itself — see {@link #manualAuthorizeUrl}.
	 */
	private static final String LEGACY_REDIRECT = "https://login.live.com/oauth20_desktop.srf";

	private static final Duration TIMEOUT = Duration.ofSeconds(20);
	private static final Duration MIN_POLL_INTERVAL = Duration.ofSeconds(1);
	/** Long enough to find a password manager and pass a 2FA prompt; short enough that a forgotten tab does not hold the port all session. */
	private static final Duration BROWSER_LOGIN_TIMEOUT = Duration.ofMinutes(10);

	private static final SecureRandom RANDOM = new SecureRandom();

	private final HttpClient http;

	public MsaAuth() {
		this.http = HttpClient.newBuilder()
				.connectTimeout(TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	/** What the add-account screen shows while it waits. */
	public record DeviceCode(String deviceCode, String userCode, String verificationUri, Duration interval, Instant expiresAt) {
	}

	/** A completed login: everything needed to build a {@link net.minecraft.client.User} plus the token to store. */
	public record Session(UUID uuid, String name, String accessToken, String xuid, String refreshToken) {
	}

	private static String clientId() {
		String configured = GammaSettings.msaClientId();
		return configured == null || configured.isBlank() ? DEFAULT_CLIENT_ID : configured.trim();
	}

	/**
	 * Microsoft runs two identity stacks and an application belongs to exactly one of them.
	 *
	 * <p>The old MSA apps — sixteen hex digits, which is what the Minecraft launcher id is — live
	 * on {@code login.live.com/oauth20_*}. Anything registered through the Azure portal since is a
	 * GUID and lives on {@code login.microsoftonline.com/.../v2.0}. Sending an id to the wrong
	 * stack is refused, so the shape of the id picks the endpoints rather than a second setting
	 * nobody would know how to answer.
	 */
	private static boolean isLegacyClientId(String id) {
		if (id.length() != 16) {
			return false;
		}
		for (int i = 0; i < id.length(); i++) {
			if (Character.digit(id.charAt(i), 16) < 0) {
				return false;
			}
		}
		return true;
	}

	private static String authorizeEndpoint(String clientId) {
		return isLegacyClientId(clientId) ? LIVE_AUTHORIZE : V2_AUTHORIZE;
	}

	private static String tokenEndpoint(String clientId) {
		return isLegacyClientId(clientId) ? LIVE_TOKEN : V2_TOKEN;
	}

	private static String scopeFor(String clientId) {
		return isLegacyClientId(clientId) ? LEGACY_SCOPE : SCOPE;
	}

	/**
	 * Whether sign-in can capture the code by itself, or has to ask the user to paste the redirected
	 * URL back.
	 *
	 * <p>Azure public clients treat {@code http://127.0.0.1} as a loopback redirect and match it by
	 * host, ignoring the port — which is what lets an ephemeral port work with nothing registered.
	 * The legacy Minecraft launcher application has no such rule: it has a fixed list of registered
	 * redirects, loopback is not on it, and asking for one is refused outright with
	 * {@code invalid_request ... redirect_uri ... is not valid}. So the built-in id gets the manual
	 * flow and a configured Azure id gets the automatic one.
	 */
	public static boolean supportsLoopback() {
		return !isLegacyClientId(clientId());
	}

	// == browser sign-in (the default) =====================================================

	/**
	 * A login waiting on the browser: a loopback server listening for the redirect, and the URL to
	 * send the browser to. Closed by {@link #awaitBrowserLogin}, or by {@link #close()} if the user
	 * gives up first.
	 */
	public static final class BrowserLogin implements AutoCloseable {

		private final HttpServer server;
		private final int port;
		private final String verifier;
		private final String state;
		private final URI authorizeUrl;
		private final CompletableFuture<Map<String, String>> callback = new CompletableFuture<>();

		private BrowserLogin(HttpServer server, int port, String verifier, String state, URI authorizeUrl) {
			this.server = server;
			this.port = port;
			this.verifier = verifier;
			this.state = state;
			this.authorizeUrl = authorizeUrl;
		}

		public URI authorizeUrl() {
			return authorizeUrl;
		}

		@Override
		public void close() {
			server.stop(0);
		}
	}

	/**
	 * Opens a loopback listener and builds the URL the browser should visit. Does not block on the
	 * user — {@link #awaitBrowserLogin} does that.
	 *
	 * <p>The port is whatever the OS hands out. Azure treats {@code http://127.0.0.1} as a loopback
	 * redirect and ignores which port it came back on, precisely so a desktop app does not have to
	 * reserve one, and the legacy stack accepts loopback the same way. That is what lets this work
	 * with no configuration at all — nothing to register, nothing to paste, nothing to collide with
	 * if the port happens to be busy.
	 */
	public BrowserLogin beginBrowserLogin() throws AuthException {
		String clientId = clientId();
		String verifier = randomUrlSafe(64);
		String state = randomUrlSafe(16);

		HttpServer server;
		try {
			// Loopback specifically, never 0.0.0.0: this endpoint accepts an authorization code, and
			// nothing off this machine has any business reaching it.
			server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		} catch (IOException e) {
			throw new AuthException("Could not open a local port to receive the sign-in. Check whether a firewall is blocking Minecraft.", true, e);
		}
		int port = server.getAddress().getPort();

		String redirectUri = "http://127.0.0.1:" + port;
		String url = authorizeEndpoint(clientId)
				+ "?client_id=" + encode(clientId)
				+ "&response_type=code"
				+ "&redirect_uri=" + encode(redirectUri)
				+ "&scope=" + encode(scopeFor(clientId))
				+ "&code_challenge_method=S256"
				+ "&code_challenge=" + challengeFor(verifier)
				+ "&state=" + state
				+ "&prompt=select_account";

		BrowserLogin login = new BrowserLogin(server, port, verifier, state, URI.create(url));
		server.createContext("/", exchange -> handleRedirect(exchange, login));
		server.setExecutor(null);
		server.start();
		return login;
	}

	private static void handleRedirect(HttpExchange exchange, BrowserLogin login) throws IOException {
		Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
		String message = params.containsKey("code")
				? "Signed in. You can close this tab and go back to Minecraft."
				: "Sign-in did not complete. You can close this tab and try again in Minecraft.";
		byte[] body = ("<!doctype html><meta charset=\"utf-8\"><title>Gamma</title>"
				+ "<body style=\"font-family:system-ui,sans-serif;background:#111216;color:#eee;"
				+ "display:flex;align-items:center;justify-content:center;height:100vh;margin:0\">"
				+ "<p>" + message + "</p>").getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
		exchange.sendResponseHeaders(200, body.length);
		try (var out = exchange.getResponseBody()) {
			out.write(body);
		}
		login.callback.complete(params);
	}

	/**
	 * Blocks until the browser comes back, then exchanges the code and runs the token chain.
	 * Always closes the listener, including on failure — a loopback port left open after a
	 * cancelled login is a small hole with no reason to exist.
	 */
	public Session awaitBrowserLogin(BrowserLogin login, BooleanSupplier cancelled) throws AuthException {
		try (login) {
			Map<String, String> params = waitForRedirect(login, cancelled);

			if (params.containsKey("error")) {
				String error = params.get("error");
				if ("access_denied".equals(error)) {
					throw new AuthException("Sign-in was declined in the browser.");
				}
				Gamma.LOGGER.warn("Account auth: browser redirect returned '{}'", error);
				throw new AuthException("Microsoft refused the sign-in (" + error + ").");
			}
			// The only thing tying this request back to the login this process started. Anything
			// else that reached the port is not our browser.
			if (!login.state.equals(params.get("state"))) {
				throw new AuthException("The sign-in response did not match the request that started it. Try again.");
			}
			String code = params.get("code");
			if (code == null) {
				throw new AuthException("Microsoft sent no authorization code. Try again.");
			}

			String clientId = clientId();
			JsonObject json = postForm(tokenEndpoint(clientId), form(
					"grant_type", "authorization_code",
					"code", code,
					"redirect_uri", "http://127.0.0.1:" + login.port,
					"client_id", clientId,
					"code_verifier", login.verifier), "completing the sign-in");
			if (json.has("error")) {
				throw describeMsaError(json);
			}
			return completeChain(string(json, "access_token"), string(json, "refresh_token"));
		}
	}

	private static Map<String, String> waitForRedirect(BrowserLogin login, BooleanSupplier cancelled) throws AuthException {
		Instant deadline = Instant.now().plus(BROWSER_LOGIN_TIMEOUT);
		while (true) {
			if (cancelled.getAsBoolean()) {
				throw new AuthException("Sign-in cancelled.");
			}
			if (Instant.now().isAfter(deadline)) {
				throw new AuthException("The sign-in was not completed in time. Try again.");
			}
			try {
				// Short waits rather than one long one, so cancelling is responsive instead of
				// hanging until the whole timeout expires.
				return login.callback.get(500, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				// Nothing yet; loop and re-check the cancel flag.
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AuthException("Sign-in interrupted.");
			} catch (ExecutionException e) {
				throw new AuthException("The local sign-in listener failed.", true, e);
			}
		}
	}

	// == manual sign-in (the built-in id) ==================================================

	/**
	 * The URL to open when {@link #supportsLoopback()} is false.
	 *
	 * <p>Microsoft sends the browser to {@link #LEGACY_REDIRECT} afterwards, which renders a page
	 * with nothing on it and {@code ?code=...} in the address bar. Nothing in this process can read
	 * another application's address bar, so the user copies it back — one paste per account added,
	 * never again afterwards, since switching runs off the stored refresh token.
	 *
	 * <p>No PKCE here. The legacy stack does not require it, and every extra parameter is another
	 * thing that can be refused on a flow that cannot be tested from here.
	 */
	public URI manualAuthorizeUrl() {
		String clientId = clientId();
		return URI.create(authorizeEndpoint(clientId)
				+ "?client_id=" + encode(clientId)
				+ "&response_type=code"
				+ "&redirect_uri=" + encode(LEGACY_REDIRECT)
				+ "&scope=" + encode(scopeFor(clientId))
				+ "&prompt=select_account");
	}

	/**
	 * Finishes a manual sign-in from whatever the user pasted.
	 *
	 * <p>Accepts the whole redirected URL or just the code, because both are things a person
	 * plausibly pastes and telling them apart is one {@code indexOf} rather than an error message.
	 */
	public Session completeManualLogin(String pasted) throws AuthException {
		String code = extractCode(pasted);
		String clientId = clientId();
		JsonObject json = postForm(tokenEndpoint(clientId), form(
				"grant_type", "authorization_code",
				"code", code,
				"redirect_uri", LEGACY_REDIRECT,
				"client_id", clientId), "completing the sign-in");
		if (json.has("error")) {
			throw describeMsaError(json);
		}
		return completeChain(string(json, "access_token"), string(json, "refresh_token"));
	}

	private static String extractCode(String pasted) throws AuthException {
		String trimmed = pasted == null ? "" : pasted.trim();
		if (trimmed.isEmpty()) {
			throw new AuthException("Paste the address of the page the browser ended up on.");
		}
		int marker = trimmed.indexOf("code=");
		if (marker >= 0) {
			String tail = trimmed.substring(marker + "code=".length());
			int end = tail.indexOf('&');
			String code = end >= 0 ? tail.substring(0, end) : tail;
			code = URLDecoder.decode(code, StandardCharsets.UTF_8);
			if (!code.isBlank()) {
				return code;
			}
		}
		// No "code=" at all: either they pasted the bare code, or they pasted the wrong thing. A URL
		// without a code is the second, and worth saying so rather than sending it to Microsoft.
		if (trimmed.startsWith("http")) {
			if (trimmed.contains("error=")) {
				throw new AuthException("That page reports the sign-in was refused. Try again.");
			}
			throw new AuthException("That address has no sign-in code in it. Finish signing in first, then copy the address bar.");
		}
		return trimmed;
	}

	// == device code (fallback) ============================================================

	/**
	 * Only reachable from the "use a code instead" link, and only useful with an Azure application
	 * id: the device code grant lives on the v2.0 stack, which the legacy default id does not
	 * belong to. {@link #describeMsaError} says as much when it is refused.
	 */
	public DeviceCode requestDeviceCode() throws AuthException {
		String body = form("client_id", clientId(), "scope", SCOPE);
		JsonObject json = postForm(V2_DEVICE_CODE, body, "requesting a login code");
		if (json.has("error")) {
			throw describeMsaError(json);
		}
		long intervalSeconds = json.has("interval") ? json.get("interval").getAsLong() : 5L;
		long expiresIn = json.has("expires_in") ? json.get("expires_in").getAsLong() : 900L;
		return new DeviceCode(
				string(json, "device_code"),
				string(json, "user_code"),
				json.has("verification_uri") ? json.get("verification_uri").getAsString() : "https://microsoft.com/link",
				Duration.ofSeconds(Math.max(intervalSeconds, MIN_POLL_INTERVAL.toSeconds())),
				Instant.now().plusSeconds(expiresIn));
	}

	// -- step 1: wait for the user to approve it -------------------------------------------

	/**
	 * Polls until the code is approved, refused, or expires. Blocks for as long as that takes, so
	 * {@code cancelled} is how the screen's cancel button gets out — it is checked every interval
	 * and answered with an {@link AuthException} rather than a value.
	 */
	public Session pollForSession(DeviceCode code, BooleanSupplier cancelled) throws AuthException {
		Duration interval = code.interval();
		while (true) {
			if (cancelled.getAsBoolean()) {
				throw new AuthException("Login cancelled.");
			}
			if (Instant.now().isAfter(code.expiresAt())) {
				throw new AuthException("That code expired before it was approved. Try again.");
			}
			try {
				Thread.sleep(interval.toMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AuthException("Login interrupted.");
			}
			if (cancelled.getAsBoolean()) {
				throw new AuthException("Login cancelled.");
			}

			JsonObject json = postForm(tokenEndpoint(clientId()), form(
					"grant_type", "urn:ietf:params:oauth:grant-type:device_code",
					"client_id", clientId(),
					"device_code", code.deviceCode()), "checking whether the code was approved");

			if (!json.has("error")) {
				return completeChain(string(json, "access_token"), string(json, "refresh_token"));
			}
			String error = json.get("error").getAsString();
			switch (error) {
				case "authorization_pending" -> {
					// Expected: they have not finished in the browser yet. Keep waiting.
				}
				// Microsoft asks for more breathing room; the spec says add five seconds.
				case "slow_down" -> interval = interval.plusSeconds(5);
				case "expired_token" -> throw new AuthException("That code expired before it was approved. Try again.");
				case "authorization_declined", "access_denied" -> throw new AuthException("Login was declined in the browser.");
				default -> throw describeMsaError(json);
			}
		}
	}

	// -- refreshing a stored account -------------------------------------------------------

	/** Turns a stored refresh token back into a live session. This is what switching accounts runs. */
	public Session refresh(String refreshToken) throws AuthException {
		JsonObject json = postForm(tokenEndpoint(clientId()), form(
				"grant_type", "refresh_token",
				"client_id", clientId(),
				"scope", scopeFor(clientId()),
				"refresh_token", refreshToken), "refreshing the saved login");
		if (json.has("error")) {
			if ("invalid_grant".equals(json.get("error").getAsString())) {
				throw new AuthException("This account's saved login is no longer valid — it was revoked, or the password changed. Remove it and add it again.");
			}
			throw describeMsaError(json);
		}
		// Microsoft rotates refresh tokens: the response usually carries a new one, and when it
		// does the old one stops working. Falling back to the existing token covers the case where
		// it does not, which is why the caller always writes back whatever comes out of here.
		String rotated = json.has("refresh_token") ? json.get("refresh_token").getAsString() : refreshToken;
		return completeChain(string(json, "access_token"), rotated);
	}

	// -- steps 2-5: Xbox Live, XSTS, Minecraft, profile ------------------------------------

	private Session completeChain(String msaAccessToken, String refreshToken) throws AuthException {
		JsonObject xbl = authenticateWithXbox(msaAccessToken);
		String xblToken = string(xbl, "Token");

		JsonObject xsts = postJson(XSTS_ENDPOINT, xstsRequest(xblToken), "authorising Xbox Live for Minecraft");
		String xstsToken = string(xsts, "Token");
		JsonObject claims = firstDisplayClaim(xsts);
		String userHash = claims.has("uhs") ? claims.get("uhs").getAsString() : null;
		String xuid = claims.has("xid") ? claims.get("xid").getAsString() : null;
		if (userHash == null) {
			throw new AuthException("Xbox Live did not return a user hash. Try again in a moment.");
		}

		JsonObject mc = postJson(MC_LOGIN_ENDPOINT, mcLoginRequest(userHash, xstsToken), "signing in to Minecraft services");
		String mcToken = string(mc, "access_token");

		JsonObject profile = getProfile(mcToken);
		return new Session(
				dashedUuid(string(profile, "id")),
				string(profile, "name"),
				mcToken,
				xuid,
				refreshToken);
	}

	/**
	 * Xbox Live wants the MSA token prefixed with {@code d=} when it came from the v2.0 stack, and
	 * bare when it came from the legacy {@code MBI_SSL} scope. Which of those applies is a detail
	 * that cannot be checked from here without a live token, so both are tried rather than a coin
	 * flipped — one extra request on the losing branch, against an otherwise dead-end failure with
	 * an error that would not explain itself.
	 *
	 * <p>The first failure is the one reported: if both are refused, the token is bad for reasons
	 * that have nothing to do with the prefix.
	 */
	private JsonObject authenticateWithXbox(String msaAccessToken) throws AuthException {
		try {
			return postJson(XBL_ENDPOINT, xblRequest("d=" + msaAccessToken), "signing in to Xbox Live");
		} catch (AuthException prefixed) {
			Gamma.LOGGER.debug("Xbox Live refused the delegation-prefixed ticket, retrying bare");
			try {
				return postJson(XBL_ENDPOINT, xblRequest(msaAccessToken), "signing in to Xbox Live");
			} catch (AuthException bare) {
				throw prefixed;
			}
		}
	}

	private static String xblRequest(String rpsTicket) {
		JsonObject properties = new JsonObject();
		properties.addProperty("AuthMethod", "RPS");
		properties.addProperty("SiteName", "user.auth.xboxlive.com");
		properties.addProperty("RpsTicket", rpsTicket);
		JsonObject root = new JsonObject();
		root.add("Properties", properties);
		root.addProperty("RelyingParty", "http://auth.xboxlive.com");
		root.addProperty("TokenType", "JWT");
		return root.toString();
	}

	private static String xstsRequest(String xblToken) {
		JsonArray tokens = new JsonArray();
		tokens.add(xblToken);
		JsonObject properties = new JsonObject();
		properties.addProperty("SandboxId", "RETAIL");
		properties.add("UserTokens", tokens);
		JsonObject root = new JsonObject();
		root.add("Properties", properties);
		root.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
		root.addProperty("TokenType", "JWT");
		return root.toString();
	}

	private static String mcLoginRequest(String userHash, String xstsToken) {
		JsonObject root = new JsonObject();
		root.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);
		return root.toString();
	}

	private JsonObject getProfile(String mcToken) throws AuthException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(MC_PROFILE_ENDPOINT))
				.timeout(TIMEOUT)
				.header("Authorization", "Bearer " + mcToken)
				.header("Accept", "application/json")
				.GET()
				.build();
		HttpResponse<String> response = send(request, "reading the Minecraft profile");
		if (response.statusCode() == 404) {
			throw new AuthException("That Microsoft account does not own Minecraft: Java Edition.");
		}
		if (response.statusCode() / 100 != 2) {
			throw statusFailure("reading the Minecraft profile", response);
		}
		return parse(response.body(), "reading the Minecraft profile");
	}

	// -- HTTP plumbing ---------------------------------------------------------------------

	private JsonObject postForm(String endpoint, String body, String what) throws AuthException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
				.timeout(TIMEOUT)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> response = send(request, what);
		// A 400 here is normal traffic, not a failure: "authorization_pending" arrives as one on
		// every poll. So the body is parsed either way and the caller reads the error field.
		if (response.statusCode() / 100 != 2 && response.statusCode() != 400) {
			throw statusFailure(what, response);
		}
		return parse(response.body(), what);
	}

	private JsonObject postJson(String endpoint, String body, String what) throws AuthException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
				.timeout(TIMEOUT)
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> response = send(request, what);
		if (response.statusCode() == 401 && endpoint.equals(XSTS_ENDPOINT)) {
			throw describeXstsError(response.body());
		}
		if (response.statusCode() / 100 != 2) {
			throw statusFailure(what, response);
		}
		return parse(response.body(), what);
	}

	private HttpResponse<String> send(HttpRequest request, String what) throws AuthException {
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new AuthException("Network error while " + what + ". Check your connection and try again.", true, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AuthException("Interrupted while " + what + ".", true, e);
		}
	}

	private static JsonObject parse(String body, String what) throws AuthException {
		try {
			return JsonParser.parseString(body).getAsJsonObject();
		} catch (Exception e) {
			throw new AuthException("Unreadable response while " + what + ".", true, e);
		}
	}

	/** Bodies are deliberately never logged or shown: successful ones contain tokens. */
	private static AuthException statusFailure(String what, HttpResponse<String> response) {
		Gamma.LOGGER.warn("Account auth: HTTP {} while {}", response.statusCode(), what);
		boolean retryable = response.statusCode() / 100 == 5 || response.statusCode() == 429;
		return new AuthException("Microsoft returned HTTP " + response.statusCode() + " while " + what + "."
				+ (retryable ? " That is usually temporary — try again shortly." : ""), retryable, null);
	}

	// -- error translation -----------------------------------------------------------------

	private static AuthException describeMsaError(JsonObject json) {
		String error = json.has("error") ? json.get("error").getAsString() : "unknown_error";
		// error_description is Microsoft's own prose and is meant to be shown, but it arrives as a
		// wall of text with a correlation id and a timestamp glued on. The first sentence is the
		// part a player can act on.
		String description = json.has("error_description") ? json.get("error_description").getAsString() : "";
		Gamma.LOGGER.warn("Account auth: Microsoft returned '{}'", error);
		String hint = switch (error) {
			case "unauthorized_client", "invalid_client" ->
					"Microsoft refused this application id. Register an Azure application and set MsaClientId in Gamma's settings.";
			case "invalid_scope" -> "Microsoft refused the requested permissions.";
			default -> firstSentence(description);
		};
		return new AuthException(hint.isBlank() ? "Microsoft rejected the login (" + error + ")." : hint);
	}

	/**
	 * The two failures people actually hit. Both come back as a 401 whose body is a bare
	 * {@code XErr} number, which is unusable as-is.
	 */
	private static AuthException describeXstsError(String body) {
		long code = 0L;
		try {
			JsonObject json = JsonParser.parseString(body).getAsJsonObject();
			if (json.has("XErr")) {
				code = json.get("XErr").getAsLong();
			}
		} catch (Exception ignored) {
			// Fall through to the generic message; there is nothing better to say.
		}
		Gamma.LOGGER.warn("Account auth: XSTS refused the token (XErr {})", code);
		return new AuthException(switch ((int) code) {
			case (int) 2148916233L -> "This Microsoft account has no Xbox profile. Sign in once at minecraft.net to create one, then try again.";
			case (int) 2148916238L -> "This account is registered as a child and needs to be added to a Microsoft family group before it can sign in.";
			case (int) 2148916235L -> "Xbox Live is not available in this account's country or region.";
			case (int) 2148916236L, (int) 2148916237L -> "This account needs adult verification before it can sign in.";
			default -> "Xbox Live refused this account" + (code == 0 ? "." : " (error " + code + ").");
		});
	}

	// -- small helpers ---------------------------------------------------------------------

	private static JsonObject firstDisplayClaim(JsonObject response) throws AuthException {
		if (response.has("DisplayClaims")) {
			JsonObject claims = response.getAsJsonObject("DisplayClaims");
			if (claims.has("xui")) {
				JsonArray xui = claims.getAsJsonArray("xui");
				if (!xui.isEmpty()) {
					return xui.get(0).getAsJsonObject();
				}
			}
		}
		throw new AuthException("Xbox Live returned no account details. Try again in a moment.");
	}

	private static String string(JsonObject json, String field) throws AuthException {
		if (!json.has(field) || json.get(field).isJsonNull()) {
			throw new AuthException("Microsoft's response was missing '" + field + "'. Try again.");
		}
		return json.get(field).getAsString();
	}

	/** Minecraft's profile endpoint returns the UUID unhyphenated; {@link UUID} will not parse that. */
	private static UUID dashedUuid(String undashed) throws AuthException {
		String trimmed = undashed.replace("-", "");
		if (trimmed.length() != 32) {
			throw new AuthException("Minecraft returned a profile id that could not be read.");
		}
		return UUID.fromString(trimmed.substring(0, 8) + "-" + trimmed.substring(8, 12) + "-"
				+ trimmed.substring(12, 16) + "-" + trimmed.substring(16, 20) + "-" + trimmed.substring(20));
	}

	private static String firstSentence(String text) {
		int stop = text.indexOf('.');
		return stop > 0 ? text.substring(0, stop + 1) : text;
	}

	// -- PKCE and query plumbing -----------------------------------------------------------

	/**
	 * PKCE: the client commits to {@code SHA-256(verifier)} on the authorize request and presents
	 * the verifier itself when redeeming the code. Without it, anything that could observe the
	 * redirect — another process watching loopback, a browser extension — could redeem the code
	 * first. There is no client secret to fall back on and there could not be one: a secret
	 * compiled into a mod anyone can download is not a secret.
	 */
	private static String randomUrlSafe(int bytes) {
		byte[] buffer = new byte[bytes];
		RANDOM.nextBytes(buffer);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
	}

	private static String challengeFor(String verifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is required of every JVM; if it is genuinely missing, nothing here can work.
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	private static Map<String, String> parseQuery(String rawQuery) {
		Map<String, String> params = new HashMap<>();
		if (rawQuery == null || rawQuery.isEmpty()) {
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

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String form(String... pairs) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < pairs.length; i += 2) {
			if (!builder.isEmpty()) {
				builder.append('&');
			}
			builder.append(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8))
					.append('=')
					.append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
		}
		return builder.toString();
	}
}

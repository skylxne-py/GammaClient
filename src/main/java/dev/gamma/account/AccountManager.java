package dev.gamma.account;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import dev.gamma.Gamma;
import dev.gamma.core.GammaExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The account list, and the one place a session switch is performed.
 *
 * <h2>Threading</h2>
 *
 * <p>Every public method here is called from a screen, so every public method is safe on the
 * client thread and does no I/O on it. The blocking parts — disk, and the four network hops in
 * {@link MsaAuth} — run on {@link GammaExecutor}, and callbacks are posted back through
 * {@code Minecraft.execute} so a screen never has to think about which thread it is on.
 *
 * <h2>Menu only</h2>
 *
 * <p>{@link #canSwitch()} refuses while a world or server is loaded, and the screens grey out
 * accordingly. This is not caution for its own sake: an open connection was authenticated as the
 * old account during the handshake, and the server keeps treating it that way. Swapping underneath
 * it desynchronises chat signing, the player list and the server's idea of who is connected, all
 * without any of it being visible until something breaks oddly. Disconnecting first is the only
 * version of this that is actually correct.
 */
public final class AccountManager {

	/**
	 * The live instance, for the screens. Same pattern as {@code Theme.instance} and the module
	 * singletons — screens are constructed by vanilla in places that cannot be handed dependencies.
	 */
	public static volatile AccountManager instance;

	private final AccountStore store = new AccountStore();
	private final MsaAuth auth = new MsaAuth();

	private final List<Account> accounts = new ArrayList<>();
	private volatile UUID selected;

	/** One login or switch at a time — the screens use this to disable their buttons. */
	private final AtomicBoolean busy = new AtomicBoolean(false);
	private volatile String status = "";

	public AccountManager() {
		instance = this;
	}

	/** Reads the store off-thread, then publishes on the client thread. Call once at startup. */
	public void load() {
		GammaExecutor.execute(() -> {
			AccountStore.Snapshot snapshot = store.load();
			Minecraft.getInstance().execute(() -> {
				accounts.clear();
				accounts.addAll(snapshot.accounts());
				selected = snapshot.selected();
				if (!accounts.isEmpty()) {
					Gamma.LOGGER.info("Loaded {} stored account(s)", accounts.size());
				}
			});
		});
	}

	// -- state the screens read ------------------------------------------------------------

	/** Most recently used first, so the account you keep switching to stays at the top. */
	public List<Account> accounts() {
		List<Account> sorted = new ArrayList<>(accounts);
		sorted.sort(Comparator.comparingLong(Account::lastUsed).reversed());
		return sorted;
	}

	public UUID selected() {
		return selected;
	}

	public boolean isBusy() {
		return busy.get();
	}

	/** What the switcher is currently doing, for the screen to print. Empty when idle. */
	public String status() {
		return status;
	}

	/** The name Minecraft is currently logged in as — not necessarily one of the stored accounts. */
	public static String currentName() {
		User user = Minecraft.getInstance().getUser();
		return user == null ? "?" : user.getName();
	}

	/** False while a world or server is loaded; see the class note. */
	public static boolean canSwitch() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.level == null && minecraft.getConnection() == null;
	}

	// -- adding an account -----------------------------------------------------------------

	/**
	 * Browser sign-in — the default way to add an account.
	 *
	 * @param onUrl     handed the Microsoft URL as soon as the listener is up, so the screen can
	 *                  open it and offer to open it again
	 * @param onSuccess the account that was added, already saved and selected
	 * @param onError   a message already fit to display
	 * @param cancelled polled while waiting; true abandons the login and closes the listener
	 */
	public void beginBrowserAdd(Consumer<URI> onUrl, Consumer<Account> onSuccess,
			Consumer<String> onError, BooleanSupplier cancelled) {
		if (!busy.compareAndSet(false, true)) {
			onError.accept("Another login is already in progress.");
			return;
		}
		status = "Opening your browser...";
		GammaExecutor.execute(() -> {
			try {
				MsaAuth.BrowserLogin login = auth.beginBrowserLogin();
				onClient(() -> {
					status = "Waiting for you to sign in...";
					onUrl.accept(login.authorizeUrl());
				});
				completeAdd(auth.awaitBrowserLogin(login, cancelled), onSuccess);
			} catch (AuthException e) {
				finishWithError(onError, e.getMessage());
			} catch (Exception e) {
				Gamma.LOGGER.error("Unexpected failure adding an account", e);
				finishWithError(onError, "Something went wrong adding that account. Check the log for details.");
			}
		});
	}

	/**
	 * Manual sign-in — what the built-in client id has to use, because it cannot register a loopback
	 * redirect. The screen opens {@link MsaAuth#manualAuthorizeUrl()} itself and calls this with
	 * whatever the user pasted back.
	 */
	public void completeManualAdd(String pasted, Consumer<Account> onSuccess, Consumer<String> onError) {
		if (!busy.compareAndSet(false, true)) {
			onError.accept("Another login is already in progress.");
			return;
		}
		status = "Finishing sign-in...";
		GammaExecutor.execute(() -> {
			try {
				completeAdd(auth.completeManualLogin(pasted), onSuccess);
			} catch (AuthException e) {
				finishWithError(onError, e.getMessage());
			} catch (Exception e) {
				Gamma.LOGGER.error("Unexpected failure adding an account", e);
				finishWithError(onError, "Something went wrong adding that account. Check the log for details.");
			}
		});
	}

	/**
	 * Device code sign-in — the fallback, for when no browser can be opened here. Same contract as
	 * {@link #beginBrowserAdd}, except the screen is handed a code to display instead of a URL.
	 */
	public void beginDeviceCodeAdd(Consumer<MsaAuth.DeviceCode> onCode, Consumer<Account> onSuccess,
			Consumer<String> onError, BooleanSupplier cancelled) {
		if (!busy.compareAndSet(false, true)) {
			onError.accept("Another login is already in progress.");
			return;
		}
		status = "Contacting Microsoft...";
		GammaExecutor.execute(() -> {
			try {
				MsaAuth.DeviceCode code = auth.requestDeviceCode();
				onClient(() -> {
					status = "Waiting for approval...";
					onCode.accept(code);
				});
				completeAdd(auth.pollForSession(code, cancelled), onSuccess);
			} catch (AuthException e) {
				finishWithError(onError, e.getMessage());
			} catch (Exception e) {
				Gamma.LOGGER.error("Unexpected failure adding an account", e);
				finishWithError(onError, "Something went wrong adding that account. Check the log for details.");
			}
		});
	}

	/** The half both sign-in flows share once Microsoft has answered. Runs on the worker thread. */
	private void completeAdd(MsaAuth.Session session, Consumer<Account> onSuccess) {
		UserApiService api = buildApiService(session.accessToken());
		Account account = new Account(session.uuid(), session.name(), session.refreshToken(),
				System.currentTimeMillis(), System.currentTimeMillis());

		// Saved before the session is applied: if applying somehow fails, the credential is still on
		// disk and the sign-in does not have to be repeated.
		onClient(() -> upsert(account));
		persist();

		onClient(() -> {
			applySession(session, api);
			selected = account.uuid();
			status = "";
			busy.set(false);
			onSuccess.accept(account);
		});
		persist();
	}

	// -- switching -------------------------------------------------------------------------

	/**
	 * Refreshes the stored token for {@code account} and installs the resulting session.
	 *
	 * <p>The refresh is not optional even when the account was used a minute ago: what is stored is
	 * a refresh token, and the thing Minecraft needs is a short-lived access token that is not
	 * stored at all. Every switch therefore costs one round trip through Microsoft, Xbox Live and
	 * Minecraft services.
	 */
	public void switchTo(Account account, Runnable onSuccess, Consumer<String> onError) {
		if (!canSwitch()) {
			onError.accept("Disconnect from the world or server first.");
			return;
		}
		if (!busy.compareAndSet(false, true)) {
			onError.accept("Another login is already in progress.");
			return;
		}
		status = "Signing in as " + account.name() + "...";
		GammaExecutor.execute(() -> {
			try {
				MsaAuth.Session session = auth.refresh(account.refreshToken());
				UserApiService api = buildApiService(session.accessToken());

				// The token Microsoft just handed back usually replaces the one we sent, and the
				// display name may have changed since the account was added.
				Account updated = account
						.withRefreshToken(session.refreshToken())
						.withName(session.name())
						.withLastUsed(System.currentTimeMillis());

				onClient(() -> {
					// Re-checked on the client thread: the network round trip above takes seconds,
					// and the user could have joined a server in the meantime.
					if (!canSwitch()) {
						status = "";
						busy.set(false);
						onError.accept("Disconnect from the world or server first.");
						return;
					}
					upsert(updated);
					applySession(session, api);
					selected = updated.uuid();
					status = "";
					busy.set(false);
					onSuccess.run();
				});
				persist();
			} catch (AuthException e) {
				finishWithError(onError, e.getMessage());
			} catch (Exception e) {
				Gamma.LOGGER.error("Unexpected failure switching account", e);
				finishWithError(onError, "Something went wrong switching accounts. Check the log for details.");
			}
		});
	}

	public void remove(Account account) {
		accounts.removeIf(stored -> stored.uuid().equals(account.uuid()));
		if (account.uuid().equals(selected)) {
			selected = null;
		}
		persist();
	}

	// -- internals -------------------------------------------------------------------------

	/**
	 * Builds the services client for the new account. Off-thread: the constructor sets up an HTTP
	 * stack, and this is the object {@code ProfileKeyPairManager} later calls {@code getKeyPair()}
	 * on — so it has to authenticate as the account being switched to, not the one being left.
	 *
	 * <p>Falling back to {@code OFFLINE} rather than failing the switch is deliberate. The only
	 * thing lost is the chat signing key, which costs signed chat on servers that demand it; losing
	 * the whole switch because a secondary service would not build is the worse trade.
	 */
	private static UserApiService buildApiService(String accessToken) {
		try {
			return new YggdrasilAuthenticationService(Minecraft.getInstance().getProxy())
					.createUserApiService(accessToken);
		} catch (Exception e) {
			Gamma.LOGGER.warn("Could not build the account services client; signed chat will be unavailable this session", e);
			return UserApiService.OFFLINE;
		}
	}

	/** The one place {@link net.minecraft.client.Minecraft}'s session is written. Client thread only. */
	private void applySession(MsaAuth.Session session, UserApiService api) {
		User user = new User(
				session.name(),
				session.uuid(),
				session.accessToken(),
				Optional.ofNullable(session.xuid()),
				// The client id here is the telemetry/session client id, which vanilla leaves empty
				// for anything it did not launch itself. Nothing reads it that we care about.
				Optional.empty());
		((AccountSwitchTarget) Minecraft.getInstance()).gamma$applySession(user, api);
	}

	/** Insert or replace by UUID, so re-adding an existing account updates it rather than duplicating. */
	private void upsert(Account account) {
		accounts.removeIf(stored -> stored.uuid().equals(account.uuid()));
		accounts.add(account);
	}

	/** Snapshots on the client thread, writes off it — the list is not thread-safe. */
	private void persist() {
		Minecraft.getInstance().execute(() -> {
			List<Account> snapshot = List.copyOf(accounts);
			UUID current = selected;
			GammaExecutor.execute(() -> store.save(snapshot, current));
		});
	}

	private void finishWithError(Consumer<String> onError, String message) {
		onClient(() -> {
			status = "";
			busy.set(false);
			onError.accept(message);
		});
	}

	private static void onClient(Runnable action) {
		Minecraft.getInstance().execute(action);
	}
}

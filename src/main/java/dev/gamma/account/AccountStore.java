package dev.gamma.account;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.gamma.Gamma;
import dev.gamma.core.GammaPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads and writes {@code gamma/accounts.json}, encrypting the refresh tokens on the way through.
 *
 * <p>Name and UUID stay in the clear. That is not an oversight: the account list has to render
 * before anything is decrypted, and neither field is a secret — a server sees both the moment you
 * join. Only {@code iv} and {@code token} carry anything worth protecting. See
 * {@link AccountCrypto} for what that protection is and is not.
 *
 * <p>Every method here blocks on disk and belongs on {@link dev.gamma.core.GammaExecutor}.
 */
public final class AccountStore {

	private static final int CURRENT_VERSION = 1;
	private static final String FILE_NAME = "accounts.json";
	private static final String KEY_FILE_NAME = ".accounts.key";

	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	/** What one load produced: the accounts that opened, and which was last selected. */
	public record Snapshot(List<Account> accounts, UUID selected) {
		public static Snapshot empty() {
			return new Snapshot(List.of(), null);
		}
	}

	private Path file() {
		return GammaPaths.root().resolve(FILE_NAME);
	}

	private Path keyFile() {
		return GammaPaths.root().resolve(KEY_FILE_NAME);
	}

	/**
	 * Best-effort read. A single unreadable entry is dropped with a warning rather than failing the
	 * whole load — a lost refresh token costs one re-login, and refusing to start over one bad row
	 * would cost every other account too.
	 */
	public Snapshot load() {
		Path file = file();
		if (!Files.isRegularFile(file)) {
			return Snapshot.empty();
		}
		AccountCrypto crypto;
		try {
			// GammaPaths.dir() creates the directory; root() alone does not, and load() can run
			// before anything else has needed it.
			Files.createDirectories(GammaPaths.root());
			crypto = AccountCrypto.load(keyFile());
		} catch (IOException e) {
			Gamma.LOGGER.error("Could not open the account key file; stored accounts are unavailable this session", e);
			return Snapshot.empty();
		}

		JsonObject root;
		try {
			root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			Gamma.LOGGER.error("Could not read {}; treating the account list as empty", file, e);
			return Snapshot.empty();
		}

		int version = root.has("version") ? root.get("version").getAsInt() : CURRENT_VERSION;
		if (version > CURRENT_VERSION) {
			Gamma.LOGGER.warn("accounts.json is from a newer Gamma version ({} > {}), loading best-effort", version, CURRENT_VERSION);
		}

		List<Account> accounts = new ArrayList<>();
		if (root.has("accounts")) {
			for (JsonElement element : root.getAsJsonArray("accounts")) {
				Account account = readAccount(element, crypto);
				if (account != null) {
					accounts.add(account);
				}
			}
		}

		UUID selected = null;
		if (root.has("selected") && !root.get("selected").isJsonNull()) {
			try {
				selected = UUID.fromString(root.get("selected").getAsString());
			} catch (IllegalArgumentException e) {
				Gamma.LOGGER.warn("accounts.json has an unreadable 'selected' id, ignoring it");
			}
		}
		return new Snapshot(accounts, selected);
	}

	private Account readAccount(JsonElement element, AccountCrypto crypto) {
		try {
			JsonObject json = element.getAsJsonObject();
			UUID uuid = UUID.fromString(json.get("uuid").getAsString());
			String name = json.get("name").getAsString();
			AccountCrypto.Sealed sealed = new AccountCrypto.Sealed(
					json.get("iv").getAsString(), json.get("token").getAsString());
			String refreshToken = crypto.open(uuid, sealed);
			long addedAt = json.has("addedAt") ? json.get("addedAt").getAsLong() : 0L;
			long lastUsed = json.has("lastUsed") ? json.get("lastUsed").getAsLong() : 0L;
			return new Account(uuid, name, refreshToken, addedAt, lastUsed);
		} catch (Exception e) {
			// Named, not dumped: the exception can carry cipher details, and the entry itself holds
			// the ciphertext. The name is enough for the user to know which one to add again.
			String name = safeName(element);
			Gamma.LOGGER.warn("Could not decrypt the stored login for '{}' — it will need adding again ({})",
					name, e.getClass().getSimpleName());
			return null;
		}
	}

	private static String safeName(JsonElement element) {
		try {
			return element.getAsJsonObject().get("name").getAsString();
		} catch (Exception e) {
			return "unknown account";
		}
	}

	/** Writes the whole list. Blocking; an atomic replace so a crash mid-write cannot truncate it. */
	public void save(List<Account> accounts, UUID selected) {
		try {
			Files.createDirectories(GammaPaths.root());
			AccountCrypto crypto = AccountCrypto.load(keyFile());

			JsonArray array = new JsonArray();
			for (Account account : accounts) {
				AccountCrypto.Sealed sealed = crypto.seal(account.uuid(), account.refreshToken());
				JsonObject json = new JsonObject();
				json.addProperty("uuid", account.uuid().toString());
				json.addProperty("name", account.name());
				json.addProperty("addedAt", account.addedAt());
				json.addProperty("lastUsed", account.lastUsed());
				json.addProperty("iv", sealed.iv());
				json.addProperty("token", sealed.token());
				array.add(json);
			}

			JsonObject root = new JsonObject();
			root.addProperty("version", CURRENT_VERSION);
			if (selected != null) {
				root.addProperty("selected", selected.toString());
			}
			root.add("accounts", array);

			writeAtomically(file(), gson.toJson(root));
		} catch (Exception e) {
			Gamma.LOGGER.error("Could not save the account list", e);
		}
	}

	private static void writeAtomically(Path file, String content) throws IOException {
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temp, content, StandardCharsets.UTF_8);
		try {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			// Some Windows filesystems refuse an atomic replace across the same directory when the
			// target is open. A plain replace is still better than writing in place.
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}

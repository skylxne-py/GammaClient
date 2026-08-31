package dev.gamma.account;

import java.util.UUID;

/**
 * One stored Minecraft account.
 *
 * <p>{@code refreshToken} is the credential — a long-lived Microsoft token that can be exchanged
 * for a fresh session at any time, and that stays valid until it is revoked at
 * account.live.com/consent/Manage. Everything else here is public information already visible to
 * any server the account joins.
 *
 * <p>That split is deliberate and {@link AccountStore} depends on it: name and UUID are written to
 * disk in the clear so the account list renders without touching the cipher, and only the refresh
 * token is encrypted.
 */
public record Account(UUID uuid, String name, String refreshToken, long addedAt, long lastUsed) {

	public Account withRefreshToken(String newToken) {
		return new Account(uuid, name, newToken, addedAt, lastUsed);
	}

	public Account withName(String newName) {
		return new Account(uuid, newName, refreshToken, addedAt, lastUsed);
	}

	public Account withLastUsed(long when) {
		return new Account(uuid, name, refreshToken, addedAt, when);
	}

	/**
	 * Never {@code toString()} this into a log. Records synthesise a {@code toString} that prints
	 * every component, which for this one means printing the refresh token — so it is overridden
	 * rather than left to leak the moment someone drops an account into a log line.
	 */
	@Override
	public String toString() {
		return "Account[" + name + " / " + uuid + "]";
	}
}

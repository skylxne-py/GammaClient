package dev.gamma.account;

/**
 * A login failure with a message already fit to put on screen.
 *
 * <p>Every throw site is expected to have translated whatever the wire said into something a
 * player can act on. Microsoft's own errors are not: XSTS reports "no Xbox account" as
 * {@code {"XErr":2148916233}} and a 404 from the profile endpoint is how "this account has never
 * bought Minecraft" arrives. Surfacing either raw produces a support question, so
 * {@link MsaAuth} maps them here and the screens print {@link #getMessage()} verbatim.
 */
public class AuthException extends Exception {

	/** True when retrying might work — network trouble, a 5xx — as opposed to a refused account. */
	private final boolean retryable;

	public AuthException(String message) {
		this(message, false, null);
	}

	public AuthException(String message, boolean retryable, Throwable cause) {
		super(message, cause);
		this.retryable = retryable;
	}

	public boolean retryable() {
		return retryable;
	}
}

package dev.gamma.account;

import dev.gamma.Gamma;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

/**
 * AES-256-GCM for the refresh tokens in {@code gamma/accounts.json}, keyed by a random keyfile
 * that lives beside it at {@code gamma/.accounts.key}.
 *
 * <h2>What this defends against, and what it does not</h2>
 *
 * <p>It defends against the token file travelling: a synced game directory, a backup, a zipped
 * instance handed to a friend, a pasted config, a screenshared folder. {@code accounts.json} on
 * its own is inert — it is name, UUID and ciphertext, nothing that can log in.
 *
 * <p>It does <em>not</em> defend against anything already executing on this machine. The key sits
 * in a file this process can read, so anything running as the same user can read it too. That is
 * the honest limit and it should not be sold as more: this stops accounts being copied, not
 * malware.
 *
 * <h2>Why the key is a random file rather than a machine fingerprint</h2>
 *
 * <p>"Machine-bound" was the goal, and a keyfile is how that is actually achieved here. The
 * obvious alternative — deriving the key from {@code user.name}, {@code os.name}, the hostname or
 * the game directory path — looks stronger and is weaker. Those values are short, guessable, and
 * frequently sitting in the same folder that was copied (the path is in the logs; the username is
 * in the path). An attacker with the folder would brute-force them in seconds. Meanwhile they
 * break for real: rename the PC, change the Windows username, or move the instance, and every
 * stored account becomes permanently undecryptable.
 *
 * <p>So the binding is 32 bytes of {@link SecureRandom} that never leave this install. It is
 * strictly more secret than any fingerprint and it survives the user renaming things.
 *
 * <h2>Failure is graceful</h2>
 *
 * <p>If the keyfile is missing or the ciphertext will not open, the affected account is dropped
 * with a warning and the rest still load. Losing a refresh token costs one re-login, so a
 * best-effort read is much better than refusing to start.
 */
public final class AccountCrypto {

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";
	private static final int KEY_BYTES = 32;
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;

	private static final SecureRandom RANDOM = new SecureRandom();

	private final SecretKey key;

	private AccountCrypto(SecretKey key) {
		this.key = key;
	}

	/**
	 * Reads the keyfile, generating one if this is the first run. Blocking — call from
	 * {@link dev.gamma.core.GammaExecutor}.
	 */
	public static AccountCrypto load(Path keyFile) throws IOException {
		byte[] material;
		if (Files.isRegularFile(keyFile)) {
			material = Files.readAllBytes(keyFile);
			if (material.length != KEY_BYTES) {
				throw new IOException("Key file " + keyFile + " is " + material.length
						+ " bytes, expected " + KEY_BYTES + ". Delete it to start over; stored accounts will need adding again.");
			}
		} else {
			material = new byte[KEY_BYTES];
			RANDOM.nextBytes(material);
			writeKeyFile(keyFile, material);
			Gamma.LOGGER.info("Generated a new account key file at {}", keyFile);
		}
		return new AccountCrypto(new SecretKeySpec(material, "AES"));
	}

	/**
	 * Owner-only where the filesystem supports it. On Windows this throws
	 * {@link UnsupportedOperationException} and the file inherits the directory's ACL, which is
	 * already user-scoped under {@code %APPDATA%} — hence a warning rather than a failure.
	 */
	private static void writeKeyFile(Path keyFile, byte[] material) throws IOException {
		Files.write(keyFile, material);
		try {
			Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
			Files.setPosixFilePermissions(keyFile, ownerOnly);
		} catch (UnsupportedOperationException | IOException e) {
			Gamma.LOGGER.debug("Could not restrict permissions on {} (not a POSIX filesystem)", keyFile);
		}
	}

	/**
	 * Encrypts one token. The account UUID goes in as additional authenticated data, so a
	 * ciphertext moved from one entry to another fails to open instead of silently logging you
	 * into the wrong account.
	 */
	public Sealed seal(UUID owner, String plaintext) throws GeneralSecurityException {
		byte[] iv = new byte[IV_BYTES];
		RANDOM.nextBytes(iv);
		Cipher cipher = Cipher.getInstance(TRANSFORMATION);
		cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
		cipher.updateAAD(aad(owner));
		byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
		Base64.Encoder encoder = Base64.getEncoder();
		return new Sealed(encoder.encodeToString(iv), encoder.encodeToString(ciphertext));
	}

	public String open(UUID owner, Sealed sealed) throws GeneralSecurityException {
		Base64.Decoder decoder = Base64.getDecoder();
		byte[] iv = decoder.decode(sealed.iv());
		byte[] ciphertext = decoder.decode(sealed.token());
		Cipher cipher = Cipher.getInstance(TRANSFORMATION);
		cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
		cipher.updateAAD(aad(owner));
		return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
	}

	private static byte[] aad(UUID owner) {
		return owner.toString().getBytes(StandardCharsets.UTF_8);
	}

	/** The two base64 strings that go on disk for one token. */
	public record Sealed(String iv, String token) {
	}
}

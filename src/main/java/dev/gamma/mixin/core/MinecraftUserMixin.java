package dev.gamma.mixin.core;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import dev.gamma.Gamma;
import dev.gamma.account.AccountSwitchTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * Makes the logged-in account swappable at runtime.
 *
 * <h2>Why a mixin</h2>
 *
 * <p>There is no event for this and there cannot be one: the session is four {@code private final}
 * fields set in {@code Minecraft}'s constructor, and Fabric exposes no API for replacing them.
 * Every account switcher in existence writes these fields directly. This is the last-resort case
 * the project conventions allows.
 *
 * <h2>The three fields, and why one is not enough</h2>
 *
 * <p>{@code user} is the obvious one — it carries the access token servers authenticate against.
 * Writing only it is the classic bug, and it looks like it works right up until the name in the UI
 * is wrong.
 *
 * <p>{@code profileFuture} is why. {@code Minecraft.getGameProfile()} does not read {@code user} at
 * all; it joins this cached future and reads the {@code ProfileResult} out of it, falling back to
 * {@code user} only when the future produced {@code null}. Leave it alone and the client keeps
 * reporting the previous account's name and UUID everywhere it asks the game who it is.
 *
 * <p>{@code profileKeyPairManager} holds the chat-signing key pair, which is issued to a specific
 * account. Left pointing at the old one, signed chat on the next server is signed with the wrong
 * account's key. {@code ProfileKeyPairManager.create} is public static, so it is simply rebuilt.
 *
 * <p>{@code userApiService} is the fourth, and rebuilding the key pair manager without it would
 * have been a half fix: the manager gets its key pair by calling {@code getKeyPair()} on this
 * service, and the service authenticates with the access token it was constructed from. Handed the
 * old one, it would faithfully fetch the <em>previous</em> account's signing key for the new
 * account — which is the failure the key pair rebuild was supposed to prevent. It cannot be built
 * here (it needs a {@code YggdrasilAuthenticationService}, and constructing one does network setup)
 * so {@link dev.gamma.account.AccountManager} builds it off-thread and passes it in.
 *
 * <h2>Known limit: skin textures</h2>
 *
 * <p>The replacement {@code ProfileResult} carries a bare {@link GameProfile} — id and name, no
 * texture properties, because fetching those means a network round trip and this method runs on
 * the client thread. In practice that is invisible: a player's own skin in-game is sent by the
 * server from its own session lookup, not read from this profile. A menu that draws your own head
 * locally may show the previous account's until the game restarts.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftUserMixin implements AccountSwitchTarget {

	@Shadow @Final @Mutable private User user;

	@Shadow @Final @Mutable private CompletableFuture<ProfileResult> profileFuture;

	@Shadow @Final @Mutable private ProfileKeyPairManager profileKeyPairManager;

	@Shadow @Final @Mutable private UserApiService userApiService;

	@Shadow @Final public File gameDirectory;

	@Override
	public void gamma$applySession(User newUser, UserApiService newApiService) {
		this.user = newUser;
		// Already completed: every caller of getGameProfile() joins this, and handing them a future
		// that is still pending would block the client thread on whoever asks first.
		this.profileFuture = CompletableFuture.completedFuture(
				new ProfileResult(new GameProfile(newUser.getProfileId(), newUser.getName())));
		this.userApiService = newApiService;
		this.profileKeyPairManager = ProfileKeyPairManager.create(newApiService, newUser, gameDirectory.toPath());
		Gamma.LOGGER.info("Switched session to {} ({})", newUser.getName(), newUser.getProfileId());
	}
}

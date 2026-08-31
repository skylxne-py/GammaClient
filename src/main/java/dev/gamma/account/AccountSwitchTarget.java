package dev.gamma.account;

import com.mojang.authlib.minecraft.UserApiService;
import net.minecraft.client.User;

/**
 * Implemented by {@code MinecraftUserMixin} so the switcher can hand {@link net.minecraft.client.Minecraft}
 * a new session without reflection.
 *
 * <p>The cast is always safe at runtime — the mixin is applied to {@code Minecraft} itself and
 * {@code required: true} in {@code gamma.mixins.json} means the game will not launch if it was not
 * — but the compiler has no way to know that, hence the interface rather than a direct call.
 */
public interface AccountSwitchTarget {

	/**
	 * Replaces the live session. Must be called on the client thread, and only while disconnected —
	 * see {@link AccountManager} for why.
	 *
	 * @param apiService a service built against the new account's access token. It has to be passed
	 *                   in rather than reused from the field, because the existing one authenticates
	 *                   as the previous account and is what issues the chat-signing key pair.
	 */
	void gamma$applySession(User user, UserApiService apiService);
}

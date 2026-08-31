package dev.gamma.mixin.misc;

import dev.gamma.config.GammaSettings;
import net.minecraft.client.multiplayer.resolver.AddressCheck;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Lets the {@code BypassBlockedServers} setting skip Mojang's server blocklist.
 *
 * <p>The list is supplied by an {@code AddressCheck} built from the {@code BlockListSupplier}
 * service, and {@link net.minecraft.client.multiplayer.resolver.ServerNameResolver#resolveAddress}
 * asks it twice: once about the resolved address and once about the address as typed. A blocked
 * host fails to <em>resolve at all</em> rather than failing to connect, which is why a blocked
 * server looks like a server that does not exist.
 *
 * <p>No Fabric event covers address resolution, and the check is not behind anything replaceable:
 * {@code ServerNameResolver.DEFAULT} is a static built once at class-init, so swapping its
 * {@code AddressCheck} would mean deciding at startup and never being able to change it. Redirecting
 * the two calls instead keeps the setting live — turn it off and the very next connection consults
 * the blocklist again.
 *
 * <p>Both redirects still call through when the setting is off, so with the bypass disabled this is
 * exactly vanilla, including for whatever the blocklist happens to contain at the time.
 */
@Mixin(net.minecraft.client.multiplayer.resolver.ServerNameResolver.class)
public class ServerNameResolverMixin {

	@Redirect(
			method = "resolveAddress",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/multiplayer/resolver/AddressCheck;isAllowed(Lnet/minecraft/client/multiplayer/resolver/ResolvedServerAddress;)Z"))
	private boolean gamma$allowResolved(AddressCheck check, ResolvedServerAddress address) {
		return GammaSettings.bypassBlockedServers() || check.isAllowed(address);
	}

	@Redirect(
			method = "resolveAddress",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/multiplayer/resolver/AddressCheck;isAllowed(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Z"))
	private boolean gamma$allowTyped(AddressCheck check, ServerAddress address) {
		return GammaSettings.bypassBlockedServers() || check.isAllowed(address);
	}

	/**
	 * The third check, on the address a DNS SRV record redirected to. It is applied through
	 * {@code Optional.filter(addressCheck::isAllowed)} rather than a plain call, and a bound method
	 * reference leaves no method body to redirect — so this redirects the {@code filter} instead.
	 * Without it a blocked host would still be unreachable through its SRV record, which is how a
	 * good share of servers are addressed.
	 */
	@Redirect(
			method = "resolveAddress",
			at = @At(value = "INVOKE", target = "Ljava/util/Optional;filter(Ljava/util/function/Predicate;)Ljava/util/Optional;"))
	private Optional<ResolvedServerAddress> gamma$allowRedirected(Optional<ResolvedServerAddress> resolved,
			Predicate<? super ResolvedServerAddress> check) {
		return GammaSettings.bypassBlockedServers() ? resolved : resolved.filter(check);
	}
}

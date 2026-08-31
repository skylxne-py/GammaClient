package dev.gamma.mixin.core;

import dev.gamma.Gamma;
import dev.gamma.core.event.events.PacketReceiveEvent;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No Fabric API event fires for a generic, any-type incoming packet — {@code ClientPlayNetworking}
 * only covers registered custom-payload channels, not vanilla packets like block/chunk updates
 * the classifiers need to observe. {@code channelRead0} is the one choke point every
 * inbound packet passes through before dispatch, so it's the mixin target.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {

	@Inject(method = "channelRead0", at = @At("HEAD"))
	private void gamma$onPacketReceive(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
		Gamma.EVENT_BUS.post(new PacketReceiveEvent(packet));
	}
}

package dev.gamma.modules.misc;

import dev.gamma.Gamma;
import dev.gamma.config.setting.IntSetting;
import dev.gamma.core.Category;
import dev.gamma.core.GammaExecutor;
import dev.gamma.core.Module;
import dev.gamma.core.event.Subscription;
import dev.gamma.core.event.events.WorldUnloadEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Reconnects to the last multiplayer server after a disconnect, using the same {@code ConnectScreen} entry point the "Join Server" button uses. */
public final class AutoReconnect extends Module {

	private final IntSetting delaySeconds = register(new IntSetting("DelaySeconds", "Seconds to wait before reconnecting.", 5, 1, 120));

	private Subscription worldUnloadSubscription;

	public AutoReconnect() {
		super("AutoReconnect", "Reconnects to the last server after a disconnect.", Category.MISC);
	}

	@Override
	protected void onEnable() {
		worldUnloadSubscription = listen(WorldUnloadEvent.class, event -> onWorldUnload());
	}

	@Override
	protected void onDisable() {
		Gamma.EVENT_BUS.unsubscribe(worldUnloadSubscription);
	}

	private void onWorldUnload() {
		Minecraft client = Minecraft.getInstance();
		ServerData server = client.getCurrentServer();
		if (server == null || client.hasSingleplayerServer()) {
			return;
		}
		GammaExecutor.schedule(() -> client.execute(() -> attemptReconnect(server)), delaySeconds.get(), TimeUnit.SECONDS);
	}

	private void attemptReconnect(ServerData server) {
		Minecraft client = Minecraft.getInstance();
		var screen = client.gui.screen();
		if (!isEnabled() || client.getConnection() != null || screen == null) {
			return;
		}
		Gamma.LOGGER.info("AutoReconnect: reconnecting to {}", server.ip);
		ServerAddress address = ServerAddress.parseString(server.ip);
		ConnectScreen.startConnecting(screen, client, address, server, false, new TransferState(Map.of(), Map.of(), false));
	}
}

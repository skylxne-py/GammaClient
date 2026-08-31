package dev.gamma.modules.misc;

import dev.gamma.Gamma;
import dev.gamma.config.setting.StringSetting;
import dev.gamma.core.Category;
import dev.gamma.core.GammaExecutor;
import dev.gamma.core.Module;
import dev.gamma.modules.misc.discord.DiscordIpcClient;
import net.minecraft.client.Minecraft;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Optional, off by default — a module base already starts disabled, so no
 * extra flag is needed for that. See {@link DiscordIpcClient} for why this hand-rolls the IPC
 * protocol instead of pulling in a Discord SDK dependency.
 */
public final class DiscordRPC extends Module {

	private static final long UPDATE_INTERVAL_SECONDS = 15;

	private final StringSetting applicationId = register(new StringSetting("ApplicationId", "Discord application (client) ID — leave blank to disable.", ""));
	private final StringSetting details = register(new StringSetting("Details", "Top presence line.", "Using Gamma"));

	private DiscordIpcClient client;
	private ScheduledFuture<?> updateTask;
	private long startEpochSeconds;

	public DiscordRPC() {
		super("DiscordRPC", "Shows a Discord Rich Presence status while playing.", Category.MISC);
	}

	/** An integration with the client, not a feature of the game — it lists in the settings screen. */
	@Override
	public boolean isClientLevel() {
		return true;
	}

	@Override
	protected void onEnable() {
		if (applicationId.get().isBlank()) {
			Gamma.LOGGER.warn("DiscordRPC: no ApplicationId configured, not connecting");
			return;
		}
		startEpochSeconds = System.currentTimeMillis() / 1000;
		GammaExecutor.execute(() -> {
			DiscordIpcClient ipc = new DiscordIpcClient();
			if (ipc.connect(applicationId.get())) {
				client = ipc;
				pushUpdate();
				scheduleRepeating();
			} else {
				Gamma.LOGGER.info("DiscordRPC: no running Discord client found, not connecting");
			}
		});
	}

	@Override
	protected void onDisable() {
		if (updateTask != null) {
			updateTask.cancel(false);
			updateTask = null;
		}
		DiscordIpcClient ipc = client;
		client = null;
		if (ipc != null) {
			GammaExecutor.execute(ipc::close);
		}
	}

	private void scheduleRepeating() {
		updateTask = GammaExecutor.schedule(() -> {
			pushUpdate();
			if (isEnabled()) {
				scheduleRepeating();
			}
		}, UPDATE_INTERVAL_SECONDS, TimeUnit.SECONDS);
	}

	private void pushUpdate() {
		DiscordIpcClient ipc = client;
		if (ipc == null || !ipc.isConnected()) {
			return;
		}
		String state = serverDescription();
		ipc.setActivity(details.get(), state, startEpochSeconds);
	}

	private static String serverDescription() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.hasSingleplayerServer()) {
			return "Singleplayer";
		}
		var server = mc.getCurrentServer();
		return server != null ? server.name : "In the menu";
	}
}

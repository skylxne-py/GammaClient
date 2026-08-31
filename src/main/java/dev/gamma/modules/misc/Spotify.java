package dev.gamma.modules.misc;

import com.mojang.blaze3d.platform.InputConstants;
import dev.gamma.Gamma;
import dev.gamma.config.setting.BoolSetting;
import dev.gamma.config.setting.DoubleSetting;
import dev.gamma.config.setting.IntSetting;
import dev.gamma.config.setting.KeybindSetting;
import dev.gamma.config.setting.StringSetting;
import dev.gamma.core.Category;
import dev.gamma.core.Module;
import dev.gamma.core.event.Subscription;
import dev.gamma.core.event.events.TickEvent;
import dev.gamma.gui.hud.HudComponent;
import dev.gamma.gui.hud.HudContext;
import dev.gamma.gui.hud.HudManager;
import dev.gamma.gui.hud.elements.SpotifyElement;
import dev.gamma.modules.misc.spotify.AlbumArt;
import dev.gamma.modules.misc.spotify.SpotifyAuth;
import dev.gamma.modules.misc.spotify.SpotifyClient;
import dev.gamma.modules.misc.spotify.SpotifyState;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * A now-playing overlay wired to the Spotify Web API — cover, title, artist, progress, and
 * previous / play-pause / next controls. The drawing lives in
 * {@link SpotifyElement}; this owns the connection, the poll cadence, and input.
 *
 * <h2>Connecting</h2>
 *
 * <p>Set {@code ClientId} to the id of a Spotify app you created at developer.spotify.com, with
 * {@code http://127.0.0.1:<CallbackPort>/callback} registered as one of its redirect URIs, then
 * turn {@code Connected} on. That opens a browser, and a refresh token is kept afterwards so this
 * is a once-ever step. The id has to be yours because a Spotify app in development mode only works
 * for accounts its owner has added — see {@link SpotifyAuth} for the full reasoning.
 *
 * <h2>What needs Premium</h2>
 *
 * <p>Reading what is playing, and the album art, work on any account. <em>Every</em> playback
 * control endpoint — previous, play/pause, next — is Premium-only and answers {@code 403} on a
 * free account. That is Spotify's rule, not a limit of this module, and it is reported in chat
 * when it happens rather than swallowed.
 *
 * <h2>Input</h2>
 *
 * <p>Two ways in, because neither covers everything on its own. The keybinds work while playing,
 * which is when a captured cursor makes clicking impossible. The click handling works while some
 * other screen is open — inventory, chat, the pause menu — since the HUD still draws underneath
 * those and the cursor is free; that is what makes the drawn buttons more than decoration.
 */
public final class Spotify extends Module {

	public static volatile Spotify instance;

	private final StringSetting clientId = register(new StringSetting("ClientId",
			"Client id of your own Spotify app (developer.spotify.com). Register the redirect URI shown by '.spotify status' on it.", ""));
	private final BoolSetting connected = register(new BoolSetting("Connected",
			"Turn on to open the Spotify login in your browser; turn off to forget the saved token.", false));
	private final IntSetting callbackPort = register(new IntSetting("CallbackPort",
			"Local port the browser is redirected back to. Must match the redirect URI registered on your Spotify app.", 57324, 1024, 65535));
	private final DoubleSetting opacity = register(new DoubleSetting("Opacity",
			"Overlay background opacity.", 0.75, 0.0, 1.0));
	private final IntSetting width = register(new IntSetting("Width",
			"Overlay width in pixels, before HUD scale. Long titles get more room; the height is fixed by the cover.", 170, 130, 320));
	private final DoubleSetting pollSeconds = register(new DoubleSetting("PollSeconds",
			"How often to ask Spotify what is playing. The progress bar runs on its own between polls, so this can be slow without looking it.", 3.0, 1.0, 30.0));
	private final BoolSetting clickToControl = register(new BoolSetting("ClickToControl",
			"Let the overlay's buttons be clicked while another screen is open (inventory, chat, pause menu).", true));
	private final BoolSetting silenceGameMusic = register(new BoolSetting("SilenceGameMusic",
			"Stop Minecraft's own background music while Spotify is playing, so the two don't overlap. Sound effects are untouched.", false));
	private final StringSetting preferredDevice = register(new StringSetting("PreferredDevice",
			"Part of the name of the device the play button should start on when nothing is playing - e.g. part of your phone or speaker name. Blank uses whatever Spotify lists first. See '.spotify devices'.", ""));

	private final KeybindSetting playPauseKey = register(new KeybindSetting("PlayPauseKey",
			"Play/pause without opening a screen.", KeybindSetting.Bind.unbound(KeybindSetting.Mode.TOGGLE)));
	private final KeybindSetting nextKey = register(new KeybindSetting("NextKey",
			"Skip to the next track.", KeybindSetting.Bind.unbound(KeybindSetting.Mode.TOGGLE)));
	private final KeybindSetting previousKey = register(new KeybindSetting("PreviousKey",
			"Go back a track.", KeybindSetting.Bind.unbound(KeybindSetting.Mode.TOGGLE)));

	private final SpotifyClient client = new SpotifyClient();
	/** Press-edge state for the four transport binds — see {@link #pollHotkeys}. */
	private final Map<SpotifyElement.Button, Boolean> keyWasDown = new EnumMap<>(SpotifyElement.Button.class);

	private HudManager hudManager;
	private Subscription tickSubscription;
	private long lastPollNanos;
	/** Mirrors {@code connected} so a change to it can be noticed on the next tick. */
	private boolean lastConnectedSetting;
	private volatile SpotifyElement.Button hoveredButton;
	private String lastRequestedArtUrl;

	public Spotify() {
		super("Spotify", "Now-playing overlay with playback controls.", Category.MISC);
		instance = this;
		// Registered once, for the lifetime of the game, rather than per enable: Fabric's screen
		// events have no unregister, so subscribing on enable would stack a listener per toggle.
		// The handler no-ops unless this module is on.
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) ->
				ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !onScreenClick(s, event.x(), event.y(), event.button())));
	}

	/** Handed the manager at startup so the element's live position/scale can be resolved for hit-testing. */
	public void attach(HudManager hudManager) {
		this.hudManager = hudManager;
		client.setClientId(clientId.get());
		client.restoreSession(() -> {
			// The stored id wins only over a blank setting: a value typed into the ClickGUI is the
			// newer intent, and this runs at startup before any profile has been loaded.
			String stored = client.storedClientId();
			if (!stored.isEmpty() && clientId.get().trim().isEmpty()) {
				clientId.set(stored);
				client.setClientId(stored);
			}
		});
	}

	public SpotifyState state() {
		return client.state();
	}

	public double opacity() {
		return opacity.get();
	}

	public int overlayWidth() {
		return width.get();
	}

	public SpotifyElement.Button hoveredButton() {
		return hoveredButton;
	}

	@Override
	protected void onEnable() {
		lastConnectedSetting = connected.get();
		lastPollNanos = 0;
		client.setClientId(clientId.get());
		setElementEnabled(true);
		tickSubscription = listen(TickEvent.class, this::onTick);
	}

	@Override
	protected void onDisable() {
		Gamma.EVENT_BUS.unsubscribe(tickSubscription);
		setElementEnabled(false);
		hoveredButton = null;
		client.cancelPendingLogin();
		// Covers are GPU memory with no other owner; nothing else will free them. Clearing the
		// last-requested URL with them is what makes the current track's cover load again on the
		// next enable rather than waiting for the song to change.
		lastRequestedArtUrl = null;
		AlbumArt.clear();
	}

	private void onTick(TickEvent event) {
		if (event.phase() != TickEvent.Phase.END) {
			return;
		}
		syncConnectionSetting();
		pollHotkeys();
		updateHover();

		if (!client.isConnected()) {
			return;
		}
		long now = System.nanoTime();
		if (now - lastPollNanos >= (long) (pollSeconds.get() * 1_000_000_000L)) {
			lastPollNanos = now;
			client.requestPoll();
		}
		SpotifyState state = client.state();
		silenceGameMusicIfWanted(state);
		String artUrl = state.track() == null ? null : state.track().artUrl();
		if (artUrl != null && !artUrl.equals(lastRequestedArtUrl)) {
			// Requested from the tick, never the render path: this starts an HTTP request, and one
			// per frame would fire dozens before the first came back.
			lastRequestedArtUrl = artUrl;
			AlbumArt.request(artUrl);
		}
	}

	/**
	 * Stops Minecraft's own background music while Spotify is rolling.
	 *
	 * <p>Deliberately {@code MusicManager.stopPlaying()} rather than turning the Music slider down:
	 * the slider is a saved game option, and a mod that quietly rewrites your settings — and leaves
	 * them rewritten if the game closes at the wrong moment — is worse than the problem. Stopping
	 * the current track changes nothing persistent, so switching this off simply lets the next one
	 * start. It runs every tick because the music manager schedules a new track on its own; each
	 * call is a null check when nothing is playing.
	 */
	private void silenceGameMusicIfWanted(SpotifyState state) {
		if (silenceGameMusic.get() && state.status() == SpotifyState.Status.PLAYING && state.playing()) {
			Minecraft.getInstance().getMusicManager().stopPlaying();
		}
	}

	/**
	 * {@code Setting} has no change callback, so the {@code Connected} toggle is watched here.
	 * That keeps connecting where the user expects it — a switch in the ClickGUI next to the
	 * client id they just pasted — instead of only in a command.
	 */
	private void syncConnectionSetting() {
		client.setClientId(clientId.get());
		client.setPreferredDevice(preferredDevice.get());
		boolean wanted = connected.get();
		if (wanted == lastConnectedSetting) {
			return;
		}
		lastConnectedSetting = wanted;
		if (wanted) {
			if (client.isConnected()) {
				// Silently doing nothing here looks identical to a login that failed, and this is
				// the exact path someone takes when they change permissions on their Spotify app and
				// flip the toggle expecting a fresh grant. Say what happened and how to force one.
				report("Already connected with a saved token - it keeps the permissions it was granted with. "
						+ "Use '.spotify connect' to authorise again, or '.spotify diagnose' to see what it can do.");
				return;
			}
			client.login(callbackPort.get(), this::report);
		} else {
			client.logout();
			report("Disconnected from Spotify");
		}
	}

	/**
	 * Edge-detected key polling, in the same shape and for the same reason as
	 * {@link dev.gamma.core.keybind.KeybindManager}: vanilla's key callback doesn't report
	 * releases. These are handled here rather than there because that manager drives one bind per
	 * module — enabling and disabling it — and these four are commands, not toggles.
	 */
	private void pollHotkeys() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gui.screen() != null) {
			// A screen has the keyboard; a bind on 'N' would otherwise fire while typing in chat.
			keyWasDown.clear();
			return;
		}
		checkHotkey(minecraft, SpotifyElement.Button.PLAY_PAUSE, playPauseKey);
		checkHotkey(minecraft, SpotifyElement.Button.NEXT, nextKey);
		checkHotkey(minecraft, SpotifyElement.Button.PREVIOUS, previousKey);
	}

	private void checkHotkey(Minecraft minecraft, SpotifyElement.Button button, KeybindSetting setting) {
		KeybindSetting.Bind bind = setting.get();
		if (!bind.isBound()) {
			keyWasDown.remove(button);
			return;
		}
		boolean down = InputConstants.isKeyDown(minecraft.getWindow(), bind.keyCode());
		boolean wasDown = keyWasDown.getOrDefault(button, false);
		keyWasDown.put(button, down);
		if (down && !wasDown) {
			client.requestCommand(SpotifyElement.commandFor(button), this::report);
		}
	}

	/** Highlights the control under the cursor, but only where there is a cursor to be under one. */
	private void updateHover() {
		Minecraft minecraft = Minecraft.getInstance();
		if (!clickToControl.get() || minecraft.gui.screen() == null) {
			hoveredButton = null;
			return;
		}
		double mouseX = minecraft.mouseHandler.getScaledXPos(minecraft.getWindow());
		double mouseY = minecraft.mouseHandler.getScaledYPos(minecraft.getWindow());
		hoveredButton = buttonAtScreenPoint(mouseX, mouseY);
	}

	private boolean onScreenClick(Screen screen, double mouseX, double mouseY, int button) {
		if (!isEnabled() || !clickToControl.get() || button != 0 || hudManager == null) {
			return false;
		}
		// The ClickGUI and HUD editor draw their own controls over this space and own their clicks.
		if (screen.getClass().getName().startsWith("dev.gamma.gui")) {
			return false;
		}
		SpotifyElement.Button hit = buttonAtScreenPoint(mouseX, mouseY);
		if (hit == null) {
			return false;
		}
		client.requestCommand(SpotifyElement.commandFor(hit), this::report);
		return true;
	}

	/**
	 * Converts a screen-space point into the element's own coordinates and asks which control it
	 * lands on. The element is drawn under a translate-and-scale transform, so the inverse of that
	 * is exactly what hit-testing needs — and taking the bounds from {@link HudManager} rather than
	 * recomputing them is what keeps the clickable area on top of the drawn one at every scale.
	 */
	private SpotifyElement.Button buttonAtScreenPoint(double mouseX, double mouseY) {
		SpotifyElement element = element();
		if (element == null || !element.isEnabled() || hudManager == null) {
			return null;
		}
		Minecraft minecraft = Minecraft.getInstance();
		HudContext ctx = hudManager.buildContext(minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight(), 0f);
		HudManager.Bounds bounds = HudManager.resolveBounds(element, minecraft.font, ctx);
		double scale = element.scale();
		if (scale <= 0) {
			return null;
		}
		return SpotifyElement.buttonAt((mouseX - bounds.x()) / scale, (mouseY - bounds.y()) / scale);
	}

	private void setElementEnabled(boolean enabled) {
		SpotifyElement element = element();
		if (element != null) {
			element.setEnabled(enabled);
		}
	}

	private SpotifyElement element() {
		if (hudManager == null) {
			return null;
		}
		for (HudComponent component : hudManager.all()) {
			if (component instanceof SpotifyElement spotify) {
				return spotify;
			}
		}
		return null;
	}

	/** Feedback goes to chat, and is bounced to the client thread because it can arrive from a worker. */
	private void report(String message) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> {
			if (minecraft.player != null) {
				minecraft.player.sendSystemMessage(Component.literal("[Spotify] " + message));
			} else {
				Gamma.LOGGER.info("Spotify: {}", message);
			}
		});
	}

	// -- command surface -----------------------------------------------------

	public void commandLogin() {
		connected.set(true);
		lastConnectedSetting = true;
		client.setClientId(clientId.get());
		client.login(callbackPort.get(), this::report);
	}

	public void commandLogout() {
		connected.set(false);
		lastConnectedSetting = false;
		client.logout();
		report("Disconnected from Spotify");
	}

	public String statusLine() {
		SpotifyState state = client.state();
		String head = state.status() == SpotifyState.Status.PLAYING
				? (state.playing() ? "Playing: " : "Paused: ") + state.track().title() + " - " + state.track().artist()
				: state.message();
		// The permissions line is the point of this command: a 403 on the save button and a 403 from
		// a free account look identical in chat, and this is what tells them apart.
		return head
				+ "\nRedirect URI: " + SpotifyAuth.redirectUri(callbackPort.get())
				+ "\nPermissions granted: " + client.grantedScopeSummary();
	}

	public void commandDiagnose() {
		client.requestDiagnostics(this::report);
	}

	public void commandDevices() {
		client.requestDevices(this::report);
	}

	public void commandPlayOn(String deviceName) {
		client.requestTransfer(deviceName, this::report);
	}
}

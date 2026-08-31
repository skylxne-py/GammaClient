package dev.gamma.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * A rate-limited client-side chime, for modules that want to be told about a find without being
 * told about forty of them.
 *
 * <p>The limit is the reason this exists rather than a one-line {@code play} call at each site.
 * Both current users fire off bulk work — chunks arriving in a burst as you fly, a scan sweeping a
 * newly loaded region — so the natural rate of "interesting thing found" is dozens per second, and
 * dozens of overlapping copies of the same sample is noise, not a notification. One sound per
 * window, extra triggers inside the window dropped rather than queued: a notification you have
 * already had is worth nothing, so there is nothing to catch up on.
 *
 * <p>Plays a non-positional client-only sound in the UI category — it has no source in the world,
 * is never sent anywhere, and is on the same footing as an inventory click. {@link #play} may be
 * called from any thread; it hops to the client thread itself, because the callers that most need
 * it are database and packet callbacks.
 *
 * <h2>Volume</h2>
 *
 * <p>{@code SimpleSoundInstance.forUI(sound, pitch)} is deliberately not used: it hardcodes an
 * instance volume of {@code 0.25f}, a quarter of full scale before the game then multiplies by the
 * master and UI sliders — quiet enough to miss over ambient world noise, which is exactly what a
 * find notification must not be. Volume is passed explicitly per call from the owning module's
 * setting instead.
 *
 * <p>{@link #MAX_VOLUME} is a real ceiling, not a convention: {@code SoundEngine} clamps the
 * instance volume to {@code [0, 1]} <em>before</em> scaling it by the category sliders, so a value
 * above 1 is silently identical to 1 rather than louder. If it is still too quiet at 1, the
 * remaining headroom is in the game's own Master/UI sliders, not here.
 */
public final class SoundNotifier {

	/** Above this, {@code SoundEngine} clamps — see the class doc. */
	public static final double MAX_VOLUME = 1.0;

	private final long minIntervalMillis;

	private volatile long lastPlayedMillis;

	public SoundNotifier(long minIntervalMillis) {
		this.minIntervalMillis = minIntervalMillis;
	}

	/**
	 * Fixed so a notification sounds the same every time it fires.
	 *
	 * <p>Most Minecraft sound events are not one sample. {@code block.amethyst_block.hit} is several
	 * recordings at different pitches, and each play picks one — plus a pitch multiplier from the
	 * sound definition's own range — using the {@code RandomSource} the sound instance was built
	 * with. {@code SimpleSoundInstance.forUI} passes {@code createUnseededRandom()}, so every play
	 * rolls again and the notification wanders around the scale.
	 *
	 * <p>Passing a fixed-seed source instead makes both draws deterministic: the same variant and the
	 * same pitch, every time. It is otherwise exactly what {@code forUI} builds — UI category,
	 * no attenuation, camera-relative — just not left to chance.
	 */
	private static final long VARIANT_SEED = 0x9E3779B97F4A7C15L;

	/** Plays the given sample unless one was played less than the configured interval ago. Safe from any thread. */
	public void play(SoundEvent sound, float pitch, double volume) {
		float clamped = (float) Math.max(0.0, Math.min(MAX_VOLUME, volume));
		if (clamped <= 0.0f) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastPlayedMillis < minIntervalMillis) {
			return;
		}
		lastPlayedMillis = now;
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> client.getSoundManager().play(new SimpleSoundInstance(
				sound.location(), SoundSource.UI, clamped, pitch, RandomSource.create(VARIANT_SEED),
				false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true)));
	}

	/** Clears the rate-limit window, so the next {@link #play} is guaranteed to be heard. */
	public void reset() {
		lastPlayedMillis = 0;
	}
}

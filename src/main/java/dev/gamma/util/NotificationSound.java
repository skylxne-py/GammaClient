package dev.gamma.util;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/** The samples a find notification can use. Deliberately a short list — see {@link SoundNotifier}. */
public enum NotificationSound {

	/** The amethyst tap. One entry rather than four, since they are variations of the same idea. */
	AMETHYST(SoundEvents.AMETHYST_BLOCK_HIT),
	BELL(SoundEvents.BELL_BLOCK),
	PLING(SoundEvents.NOTE_BLOCK_PLING.value()),
	CHIME(SoundEvents.NOTE_BLOCK_CHIME.value()),
	BEACON(SoundEvents.BEACON_POWER_SELECT),
	EXPERIENCE(SoundEvents.EXPERIENCE_ORB_PICKUP);

	private final SoundEvent event;

	NotificationSound(SoundEvent event) {
		this.event = event;
	}

	public SoundEvent event() {
		return event;
	}
}

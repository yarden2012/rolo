package io.github.yarden2012.ledgesense;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/** Sounds a low warning when you get hit while a deadly edge is within knockback range. */
public class LedgeAlarm {
	private static final int DANGER_DISTANCE = 2;
	private static final int FLASH_TICKS = 30;
	private static final int COOLDOWN_TICKS = 20;

	private final LedgeSenseConfig config;
	private final LedgeScanner scanner;
	private int lastHurtTime;
	private int flashTicks;
	private int cooldown;

	public LedgeAlarm(LedgeSenseConfig config, LedgeScanner scanner) {
		this.config = config;
		this.scanner = scanner;
	}

	public void tick(Minecraft client, LocalPlayer player) {
		boolean justHit = player.hurtTime > lastHurtTime;
		lastHurtTime = player.hurtTime;

		if (flashTicks > 0) {
			flashTicks--;
		}

		if (cooldown > 0) {
			cooldown--;
		}

		if (!justHit || !nearDeadlyEdge()) {
			return;
		}

		flashTicks = FLASH_TICKS;

		if (config.hitAlarm && cooldown == 0) {
			client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), 0.6F, 1.0F));
			cooldown = COOLDOWN_TICKS;
		}
	}

	private boolean nearDeadlyEdge() {
		for (Hazard hazard : scanner.hazards()) {
			if (hazard.severity() == Hazard.Severity.DEADLY && hazard.distance() <= DANGER_DISTANCE) {
				return true;
			}
		}

		return false;
	}

	public boolean isFlashing() {
		return flashTicks > 0;
	}

	public int flashTicks() {
		return flashTicks;
	}
}

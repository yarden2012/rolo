package io.github.yarden2012.hitrhythm;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Grades every attack the local player makes and keeps the session stats.
 *
 * <p>The thresholds mirror vanilla's {@code Player.attack}: an attack counts as
 * "full strength" (and can crit or deal sprint knockback) only above 90% charge,
 * and damage scales with {@code 0.2 + 0.8 * charge²}.
 */
public class HitTracker {
	/** Vanilla's cut-off for crits and sprint knockback. */
	public static final float CLEAN_THRESHOLD = 0.9F;

	public enum Grade {
		PERFECT("hitrhythm.grade.perfect", 0x55FF55),
		GOOD("hitrhythm.grade.good", 0xFFFF55),
		EARLY("hitrhythm.grade.early", 0xFF5555);

		private final String key;
		private final int rgb;

		Grade(String key, int rgb) {
			this.key = key;
			this.rgb = rgb;
		}

		public String key() {
			return key;
		}

		public int rgb() {
			return rgb;
		}

		static Grade of(float charge) {
			if (charge >= 1.0F) {
				return PERFECT;
			}

			return charge > CLEAN_THRESHOLD ? GOOD : EARLY;
		}
	}

	public record GradedHit(Grade grade, float charge, boolean crit, boolean knockback, long timeMs) {
		/** The fraction of full damage this hit dealt before enchantments. */
		public float damageFactor() {
			return 0.2F + charge * charge * 0.8F;
		}
	}

	private final HitRhythmConfig config;

	private int hits;
	private int cleanHits;
	private int perfectHits;
	private int crits;
	private int knockbackHits;
	private double chargeSum;
	private int cleanStreak;
	private int bestCleanStreak;
	private GradedHit lastHit;

	private int combatTicks;
	private int lastHurtTime;
	private boolean wasCharged = true;

	public HitTracker(HitRhythmConfig config) {
		this.config = config;
	}

	/** Called right before the client sends an attack, while the charge is still intact. */
	public void onAttack(Player player, Entity target) {
		if (!(target instanceof LivingEntity) || (config.playersOnly && !(target instanceof Player))) {
			return;
		}

		float charge = player.getAttackStrengthScale(0.5F);
		boolean clean = charge > CLEAN_THRESHOLD;
		boolean knockback = clean && player.isSprinting();
		boolean crit = clean
				&& player.fallDistance > 0.0
				&& !player.onGround()
				&& !player.onClimbable()
				&& !player.isInWater()
				&& !player.isMobilityRestricted()
				&& !player.isPassenger()
				&& !player.isSprinting();

		Grade grade = Grade.of(charge);
		lastHit = new GradedHit(grade, charge, crit, knockback, System.currentTimeMillis());

		hits++;
		chargeSum += charge;

		if (clean) {
			cleanHits++;
			cleanStreak++;
			bestCleanStreak = Math.max(bestCleanStreak, cleanStreak);
		} else {
			cleanStreak = 0;
		}

		if (grade == Grade.PERFECT) {
			perfectHits++;
		}

		if (crit) {
			crits++;
		}

		if (knockback) {
			knockbackHits++;
		}

		enterCombat();
	}

	public void tick(Minecraft client) {
		LocalPlayer player = client.player;

		if (player == null) {
			combatTicks = 0;
			return;
		}

		// Getting hit also puts you in combat, so the cue works when you're the one being chased.
		if (player.hurtTime > lastHurtTime) {
			enterCombat();
		}

		lastHurtTime = player.hurtTime;

		if (combatTicks > 0) {
			combatTicks--;
		}

		boolean charged = player.getAttackStrengthScale(0.0F) >= 1.0F;

		if (charged && !wasCharged && config.readyCue && inCombat()) {
			client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), config.cuePitch, config.cueVolume));
		}

		wasCharged = charged;
	}

	private void enterCombat() {
		combatTicks = Math.max(1, config.combatWindowSeconds) * 20;
	}

	public boolean inCombat() {
		return combatTicks > 0;
	}

	public void reset() {
		hits = 0;
		cleanHits = 0;
		perfectHits = 0;
		crits = 0;
		knockbackHits = 0;
		chargeSum = 0;
		cleanStreak = 0;
		bestCleanStreak = 0;
		lastHit = null;
	}

	public GradedHit lastHit() {
		return lastHit;
	}

	public int hits() {
		return hits;
	}

	public int crits() {
		return crits;
	}

	public int knockbackHits() {
		return knockbackHits;
	}

	public int cleanStreak() {
		return cleanStreak;
	}

	public int bestCleanStreak() {
		return bestCleanStreak;
	}

	public int averageChargePercent() {
		return hits == 0 ? 0 : (int) Math.round(chargeSum / hits * 100);
	}

	public int cleanPercent() {
		return percent(cleanHits);
	}

	public int perfectPercent() {
		return percent(perfectHits);
	}

	private int percent(int count) {
		return hits == 0 ? 0 : Math.round(count * 100.0F / hits);
	}
}

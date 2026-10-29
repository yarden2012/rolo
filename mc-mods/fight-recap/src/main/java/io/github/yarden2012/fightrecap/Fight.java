package io.github.yarden2012.fightrecap;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/** Everything recorded about one fight. Mutable while the fight runs, then kept for history. */
public class Fight {
	public enum Outcome {
		WIN("fightrecap.outcome.win", 0xFF55FF55),
		LOSS("fightrecap.outcome.loss", 0xFFFF5555),
		ENDED("fightrecap.outcome.ended", 0xFFAAAAAA);

		private final String key;
		private final int color;

		Outcome(String key, int color) {
			this.key = key;
			this.color = color;
		}

		public String key() {
			return key;
		}

		public int color() {
			return color;
		}
	}

	public final LocalDateTime startedAt = LocalDateTime.now();
	public final long startMs = System.currentTimeMillis();
	public long endMs;
	public Outcome outcome = Outcome.ENDED;

	public final Set<String> opponents = new LinkedHashSet<>();

	public int swings;
	public int hitsLanded;
	public int crits;
	public int combo;
	public int bestCombo;

	public int hitsTaken;
	public float damageTaken;
	public float healed;
	public int theirCombo;
	public int theirBestCombo;

	public int gapples;
	public int pearls;
	public int potions;
	public int totemsPopped;
	public int opponentTotemsPopped;
	public int kills;

	public void landed(boolean crit) {
		hitsLanded++;

		if (crit) {
			crits++;
		}

		combo++;
		bestCombo = Math.max(bestCombo, combo);
		theirCombo = 0;
	}

	public void tookHit() {
		hitsTaken++;
		theirCombo++;
		theirBestCombo = Math.max(theirBestCombo, theirCombo);
		combo = 0;
	}

	public int exchanges() {
		return hitsLanded + hitsTaken;
	}

	public int accuracyPercent() {
		return swings == 0 ? 0 : Math.round(hitsLanded * 100.0F / swings);
	}

	public long durationSeconds() {
		return ((endMs == 0 ? System.currentTimeMillis() : endMs) - startMs) / 1000;
	}

	public String durationText() {
		long seconds = durationSeconds();
		return String.format("%d:%02d", seconds / 60, seconds % 60);
	}

	public String opponentNames() {
		return String.join(", ", opponents);
	}
}

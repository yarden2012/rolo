package io.github.yarden2012.fightrecap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

/** Appends one CSV row per fight to {@code fightrecap/fights.csv} in the game directory. */
public final class FightLog {
	private static final Logger LOGGER = LoggerFactory.getLogger("fightrecap");
	private static final Path FILE = FabricLoader.getInstance().getGameDir().resolve("fightrecap").resolve("fights.csv");
	private static final String HEADER = "started,outcome,opponents,duration_s,swings,hits,accuracy_pct,crits,best_combo,"
			+ "hits_taken,damage_taken,their_best_combo,healed,gapples,pearls,potions,totems_popped,opponent_totems_popped,kills\n";

	private FightLog() {
	}

	/** @return whether the row was written */
	public static boolean append(Fight fight) {
		String row = String.join(",",
				fight.startedAt.withNano(0).toString(),
				fight.outcome.name(),
				quote(fight.opponentNames()),
				String.valueOf(fight.durationSeconds()),
				String.valueOf(fight.swings),
				String.valueOf(fight.hitsLanded),
				String.valueOf(fight.accuracyPercent()),
				String.valueOf(fight.crits),
				String.valueOf(fight.bestCombo),
				String.valueOf(fight.hitsTaken),
				String.format(Locale.ROOT, "%.1f", fight.damageTaken),
				String.valueOf(fight.theirBestCombo),
				String.format(Locale.ROOT, "%.1f", fight.healed),
				String.valueOf(fight.gapples),
				String.valueOf(fight.pearls),
				String.valueOf(fight.potions),
				String.valueOf(fight.totemsPopped),
				String.valueOf(fight.opponentTotemsPopped),
				String.valueOf(fight.kills)) + "\n";

		try {
			Files.createDirectories(FILE.getParent());

			if (!Files.exists(FILE)) {
				Files.writeString(FILE, HEADER);
			}

			Files.writeString(FILE, row, StandardOpenOption.APPEND);
			return true;
		} catch (IOException e) {
			LOGGER.error("Could not write {}", FILE, e);
			return false;
		}
	}

	private static String quote(String value) {
		return "\"" + value.replace("\"", "\"\"") + "\"";
	}
}

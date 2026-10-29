package io.github.yarden2012.fightrecap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Player-tweakable settings, stored as JSON in {@code config/fightrecap.json}.
 * Missing keys keep the defaults below, so old files keep working after updates.
 */
public class FightRecapConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("fightrecap");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("fightrecap.json");

	/** Print the recap in chat as soon as a fight ends. */
	public boolean autoRecap = true;

	/** Append every fight to {@code fightrecap/fights.csv} in the game directory. */
	public boolean csvLog = true;

	/** Small live score line at the top of the screen during a fight. */
	public boolean liveHud = true;

	/** Only fights against players count. Turn off to practice on mobs. */
	public boolean playersOnly = true;

	/** A fight ends after this many seconds with no hits either way. */
	public int timeoutSeconds = 12;

	/** Fights with fewer hits than this (landed + taken) are too small to report. */
	public int minExchanges = 3;

	public static FightRecapConfig load() {
		if (Files.exists(FILE)) {
			try {
				FightRecapConfig config = GSON.fromJson(Files.readString(FILE), FightRecapConfig.class);

				if (config != null) {
					return config;
				}
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Could not read {}, starting with defaults", FILE, e);
			}
		}

		FightRecapConfig config = new FightRecapConfig();
		config.save();
		return config;
	}

	public void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(this));
		} catch (IOException e) {
			LOGGER.error("Could not save {}", FILE, e);
		}
	}
}

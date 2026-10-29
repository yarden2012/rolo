package io.github.yarden2012.hitrhythm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Player-tweakable settings, stored as JSON in {@code config/hitrhythm.json}.
 * Missing keys keep the defaults below, so old files keep working after updates.
 */
public class HitRhythmConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("hitrhythm");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("hitrhythm.json");

	/** Play a soft tick the moment the weapon becomes fully charged (only while in combat). */
	public boolean readyCue = true;
	public float cueVolume = 0.6F;
	public float cuePitch = 1.8F;

	/** Charge meter under the crosshair, with a notch at the 90% crit/knockback threshold. */
	public boolean meter = true;

	/** Floating PERFECT / GOOD / EARLY grade above the crosshair after each hit. */
	public boolean gradePopup = true;

	/** Session stats panel in the top-left corner. */
	public boolean panel = false;

	/** Only grade hits on players (otherwise mobs count too, handy for practice). */
	public boolean playersOnly = false;

	/** How long after the last hit given or taken you count as "in combat". */
	public int combatWindowSeconds = 8;

	public static HitRhythmConfig load() {
		if (Files.exists(FILE)) {
			try {
				HitRhythmConfig config = GSON.fromJson(Files.readString(FILE), HitRhythmConfig.class);

				if (config != null) {
					return config;
				}
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Could not read {}, starting with defaults", FILE, e);
			}
		}

		HitRhythmConfig config = new HitRhythmConfig();
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

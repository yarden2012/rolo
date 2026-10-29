package io.github.yarden2012.ledgesense;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Player-tweakable settings, stored as JSON in {@code config/ledgesense.json}.
 * Missing keys keep the defaults below, so old files keep working after updates.
 */
public class LedgeSenseConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("ledgesense");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("ledgesense.json");

	public boolean enabled = true;

	/** How far out (in blocks) to look for edges. Roughly one or two knockback hits' worth. */
	public int scanRadius = 3;

	/** Drops shallower than this many blocks are ignored. */
	public int minDrop = 4;

	/** Distance of the arrow ring from the crosshair, in GUI pixels. */
	public int ringRadius = 30;

	/** Text label next to the most dangerous arrow. */
	public boolean labels = true;

	/** Alarm sound when you take a hit next to a deadly edge. */
	public boolean hitAlarm = true;

	/** Fade the ring while sneaking, since sneaking already stops you walking off edges. */
	public boolean dimWhileSneaking = true;

	public int clampedScanRadius() {
		return Math.clamp(scanRadius, 1, 6);
	}

	public static LedgeSenseConfig load() {
		if (Files.exists(FILE)) {
			try {
				LedgeSenseConfig config = GSON.fromJson(Files.readString(FILE), LedgeSenseConfig.class);

				if (config != null) {
					return config;
				}
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Could not read {}, starting with defaults", FILE, e);
			}
		}

		LedgeSenseConfig config = new LedgeSenseConfig();
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

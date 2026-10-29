package io.github.yarden2012.threatpings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Player-tweakable settings, stored as JSON in {@code config/threatpings.json}.
 * Missing keys keep the defaults below, so old files keep working after updates.
 */
public class ThreatPingsConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("threatpings");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("threatpings.json");

	public boolean enabled = true;

	/** Sounds further away than this (in blocks) are ignored, even if audible. */
	public int maxDistance = 48;

	/** How long a ping stays on screen. */
	public float durationSeconds = 3.0F;

	/** Distance of the ping ring from the crosshair, in GUI pixels. */
	public int ringRadius = 48;

	public boolean showDistance = true;

	/** Ping kinds that are switched off, by id (see /threatpings kinds). */
	public Set<String> disabledKinds = new LinkedHashSet<>();

	public boolean isEnabled(ThreatKind kind) {
		return !disabledKinds.contains(kind.id());
	}

	public static ThreatPingsConfig load() {
		if (Files.exists(FILE)) {
			try {
				ThreatPingsConfig config = GSON.fromJson(Files.readString(FILE), ThreatPingsConfig.class);

				if (config != null) {
					return config;
				}
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Could not read {}, starting with defaults", FILE, e);
			}
		}

		ThreatPingsConfig config = new ThreatPingsConfig();
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

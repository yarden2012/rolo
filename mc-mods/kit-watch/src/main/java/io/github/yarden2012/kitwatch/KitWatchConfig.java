package io.github.yarden2012.kitwatch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Player-tweakable settings, stored as JSON in {@code config/kitwatch.json}.
 * Missing keys keep the defaults below, so old files keep working after updates.
 */
public class KitWatchConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("kitwatch");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("kitwatch.json");

	/** The supply/armor column. Alerts still fire while it's hidden. */
	public boolean panel = true;

	/** Which screen edge the column sits on: "left" or "right". */
	public String side = "left";

	/** Keep supplies you have none of in the column (shown with a red 0). */
	public boolean showEmpty = false;

	public boolean sounds = true;

	/** Warn with RE-TOTEM when the offhand totem is used up and you still carry spares. */
	public boolean totemGuard = true;

	/** Armor alert threshold, as remaining durability percent. A second alert fires at a quarter of this. */
	public int armorWarnPercent = 20;

	/** Start counting down beneficial effects when this many seconds are left. */
	public int effectWarnSeconds = 10;

	/** Warn when a supply drops to this count or lower. Keys are the supply ids. */
	public Map<String, Integer> lowStock = new LinkedHashMap<>();

	{
		lowStock.put("totems", 1);
		lowStock.put("gapples", 2);
		lowStock.put("pearls", 2);
		lowStock.put("wind_charges", 2);
		lowStock.put("arrows", 8);
		lowStock.put("healing", 1);
	}

	public int lowStock(Supply supply) {
		return lowStock.getOrDefault(supply.id(), 0);
	}

	public boolean onRight() {
		return "right".equalsIgnoreCase(side);
	}

	public static KitWatchConfig load() {
		if (Files.exists(FILE)) {
			try {
				KitWatchConfig config = GSON.fromJson(Files.readString(FILE), KitWatchConfig.class);

				if (config != null) {
					return config;
				}
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Could not read {}, starting with defaults", FILE, e);
			}
		}

		KitWatchConfig config = new KitWatchConfig();
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

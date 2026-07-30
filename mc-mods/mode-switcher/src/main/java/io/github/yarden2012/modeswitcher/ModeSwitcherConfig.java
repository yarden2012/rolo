package io.github.yarden2012.modeswitcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.GameOptions;

/**
 * Persistent state: which mode is active, which settings the player chose to
 * switch, and the captured option values for each profile.
 * Stored as JSON in {@code config/modeswitcher.json}.
 */
public class ModeSwitcherConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("modeswitcher");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("modeswitcher.json");

	private Mode currentMode = Mode.VANILLA;
	private final Set<String> enabledSettings = new LinkedHashSet<>();
	private final Map<Mode, Map<String, JsonElement>> profiles = new EnumMap<>(Mode.class);

	public ModeSwitcherConfig() {
		for (Mode mode : Mode.values()) {
			profiles.put(mode, new HashMap<>());
		}

		// Settings that get switched until the player picks their own set.
		enabledSettings.add("fov");
		enabledSettings.add("sensitivity");
		enabledSettings.add("gamma");
		enabledSettings.add("fov_effects");
	}

	public Mode currentMode() {
		return currentMode;
	}

	public void setCurrentMode(Mode mode) {
		this.currentMode = mode;
	}

	public boolean isEnabled(String settingId) {
		return enabledSettings.contains(settingId);
	}

	public void setEnabled(String settingId, boolean enabled) {
		if (enabled) {
			enabledSettings.add(settingId);
		} else {
			enabledSettings.remove(settingId);
		}
	}

	public JsonElement storedValue(Mode mode, String settingId) {
		return profiles.get(mode).get(settingId);
	}

	/** Whether a profile has ever been saved. */
	public boolean hasCapture(Mode mode) {
		return !profiles.get(mode).isEmpty();
	}

	/**
	 * Snapshots the current in-game options into the given profile. All known
	 * settings are captured (not just enabled ones) so enabling a setting
	 * later still has a sensible stored value.
	 */
	public void captureProfile(Mode mode, GameOptions options) {
		Map<String, JsonElement> profile = profiles.get(mode);

		for (ManagedSetting<?> setting : ManagedSettings.ALL) {
			profile.put(setting.id(), setting.captureJson(options));
		}
	}

	/**
	 * Applies the stored values of every enabled setting to the live options.
	 *
	 * @return how many settings were applied
	 */
	public int applyProfile(Mode mode, GameOptions options) {
		Map<String, JsonElement> profile = profiles.get(mode);
		int applied = 0;

		for (ManagedSetting<?> setting : ManagedSettings.ALL) {
			if (!enabledSettings.contains(setting.id())) {
				continue;
			}

			JsonElement stored = profile.get(setting.id());

			if (stored != null && setting.applyJson(options, stored)) {
				applied++;
			}
		}

		options.write();
		return applied;
	}

	public static ModeSwitcherConfig load() {
		ModeSwitcherConfig config = new ModeSwitcherConfig();

		if (Files.exists(FILE)) {
			try {
				config.readFrom(JsonParser.parseString(Files.readString(FILE)).getAsJsonObject());
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Could not read {}, starting with defaults", FILE, e);
			}
		}

		return config;
	}

	private void readFrom(JsonObject root) {
		if (root.has("current_mode")) {
			currentMode = Mode.byId(root.get("current_mode").getAsString());
		}

		if (root.has("enabled_settings")) {
			enabledSettings.clear();

			for (JsonElement element : root.getAsJsonArray("enabled_settings")) {
				enabledSettings.add(element.getAsString());
			}
		}

		if (root.has("profiles")) {
			JsonObject profilesObj = root.getAsJsonObject("profiles");

			for (Mode mode : Mode.values()) {
				if (profilesObj.has(mode.id())) {
					Map<String, JsonElement> profile = profiles.get(mode);

					for (Map.Entry<String, JsonElement> entry : profilesObj.getAsJsonObject(mode.id()).entrySet()) {
						profile.put(entry.getKey(), entry.getValue());
					}
				}
			}
		}
	}

	public void save() {
		JsonObject root = new JsonObject();
		root.addProperty("current_mode", currentMode.id());

		JsonArray enabled = new JsonArray();

		for (String id : enabledSettings) {
			enabled.add(id);
		}

		root.add("enabled_settings", enabled);

		JsonObject profilesObj = new JsonObject();

		for (Mode mode : Mode.values()) {
			JsonObject profile = new JsonObject();
			profiles.get(mode).forEach(profile::add);
			profilesObj.add(mode.id(), profile);
		}

		root.add("profiles", profilesObj);

		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(root));
		} catch (IOException e) {
			LOGGER.error("Could not save {}", FILE, e);
		}
	}
}

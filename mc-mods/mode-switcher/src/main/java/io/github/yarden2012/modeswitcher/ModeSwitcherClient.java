package io.github.yarden2012.modeswitcher;

import org.lwjgl.glfw.GLFW;

import io.github.yarden2012.modeswitcher.gui.ModeSwitcherConfigScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class ModeSwitcherClient implements ClientModInitializer {
	public static final String MOD_ID = "modeswitcher";

	private static ModeSwitcherConfig config;
	private static KeyBinding toggleKey;
	private static KeyBinding menuKey;

	@Override
	public void onInitializeClient() {
		config = ModeSwitcherConfig.load();

		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
		toggleKey = KeyBindingHelper.registerKeyBinding(
				new KeyBinding("key." + MOD_ID + ".toggle", GLFW.GLFW_KEY_G, category));
		menuKey = KeyBindingHelper.registerKeyBinding(
				new KeyBinding("key." + MOD_ID + ".menu", GLFW.GLFW_KEY_UNKNOWN, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (toggleKey.wasPressed()) {
				toggle(client);
			}

			while (menuKey.wasPressed()) {
				client.setScreen(new ModeSwitcherConfigScreen(null));
			}
		});
	}

	public static ModeSwitcherConfig config() {
		return config;
	}

	/** Switches to the other profile and applies its stored settings. */
	public static void toggle(MinecraftClient client) {
		Mode target = config.currentMode().opposite();

		if (!config.hasCapture(target)) {
			feedback(client, Text.translatable("modeswitcher.message.no_profile", target.label()));
			return;
		}

		int applied = config.applyProfile(target, client.options);
		config.setCurrentMode(target);
		config.save();
		feedback(client, Text.translatable("modeswitcher.message.switched", target.label(), applied));
	}

	private static void feedback(MinecraftClient client, Text message) {
		if (client.player != null) {
			client.player.sendMessage(message, true);
		}
	}
}

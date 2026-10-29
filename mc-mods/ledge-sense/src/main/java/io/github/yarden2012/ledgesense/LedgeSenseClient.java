package io.github.yarden2012.ledgesense;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

public class LedgeSenseClient implements ClientModInitializer {
	public static final String MOD_ID = "ledgesense";

	/** Once you've been airborne this long you're falling, not jumping, so stale arrows are dropped. */
	private static final int MAX_AIR_TICKS = 20;

	private static LedgeSenseConfig config;
	private static LedgeScanner scanner;
	private static LedgeAlarm alarm;
	private static KeyMapping toggleKey;
	private static int airTicks;
	private static int tickCounter;

	@Override
	public void onInitializeClient() {
		config = LedgeSenseConfig.load();
		scanner = new LedgeScanner(config);
		alarm = new LedgeAlarm(config, scanner);

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + MOD_ID + ".toggle", GLFW.GLFW_KEY_J, category));

		ClientTickEvents.END_CLIENT_TICK.register(LedgeSenseClient::tick);
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Identifier.fromNamespaceAndPath(MOD_ID, "ring"), new LedgeSenseHud(config, scanner, alarm));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
				ClientCommands.literal(MOD_ID)
						.then(ClientCommands.literal("on").executes(context -> setEnabled(context.getSource().getClient(), true)))
						.then(ClientCommands.literal("off").executes(context -> setEnabled(context.getSource().getClient(), false)))
						.then(number("radius", 1, 6, value -> config.scanRadius = value))
						.then(number("mindrop", 2, 64, value -> config.minDrop = value))
						.then(number("ring", 12, 120, value -> config.ringRadius = value))
						.then(toggle("labels", value -> config.labels = value))
						.then(toggle("alarm", value -> config.hitAlarm = value))
						.then(toggle("dimsneak", value -> config.dimWhileSneaking = value))));
	}

	private static void tick(Minecraft client) {
		while (toggleKey.consumeClick()) {
			setEnabled(client, !config.enabled);
		}

		LocalPlayer player = client.player;

		if (!config.enabled || player == null || client.level == null || !canFall(player)) {
			scanner.clear();
			return;
		}

		if (player.onGround()) {
			airTicks = 0;

			// Every other tick is plenty for walking speed and keeps the column scans cheap.
			if (++tickCounter % 2 == 0) {
				scanner.scan(player, client.level);
			}
		} else if (++airTicks > MAX_AIR_TICKS) {
			scanner.clear();
		}

		alarm.tick(client, player);
	}

	private static boolean canFall(LocalPlayer player) {
		return !player.isSpectator()
				&& !player.isCreative()
				&& !player.getAbilities().flying
				&& !player.isFallFlying()
				&& !player.isPassenger();
	}

	private static int setEnabled(Minecraft client, boolean enabled) {
		config.enabled = enabled;
		config.save();
		client.gui.setOverlayMessage(Component.translatable(enabled ? "ledgesense.message.on" : "ledgesense.message.off"), false);
		return 1;
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> toggle(String name, Consumer<Boolean> setter) {
		return ClientCommands.literal(name)
				.then(ClientCommands.literal("on").executes(context -> set(context.getSource(), name, () -> setter.accept(true), "on")))
				.then(ClientCommands.literal("off").executes(context -> set(context.getSource(), name, () -> setter.accept(false), "off")));
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> number(String name, int min, int max, IntConsumer setter) {
		return ClientCommands.literal(name)
				.then(ClientCommands.argument("value", IntegerArgumentType.integer(min, max)).executes(context -> {
					int value = IntegerArgumentType.getInteger(context, "value");
					return set(context.getSource(), name, () -> setter.accept(value), String.valueOf(value));
				}));
	}

	private static int set(FabricClientCommandSource source, String name, Runnable change, String shown) {
		change.run();
		config.save();
		source.sendFeedback(Component.translatable("ledgesense.message.setting", name, shown));
		return 1;
	}
}

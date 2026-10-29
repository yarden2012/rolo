package io.github.yarden2012.kitwatch;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.KeyMapping;
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

public class KitWatchClient implements ClientModInitializer {
	public static final String MOD_ID = "kitwatch";

	private static KitWatchConfig config;
	private static KitMonitor monitor;
	private static KeyMapping panelKey;

	@Override
	public void onInitializeClient() {
		config = KitWatchConfig.load();
		monitor = new KitMonitor(config);

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		panelKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + MOD_ID + ".panel", GLFW.GLFW_KEY_K, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			monitor.tick(client);

			while (panelKey.consumeClick()) {
				config.panel = !config.panel;
				config.save();
				client.gui.setOverlayMessage(Component.translatable(config.panel ? "kitwatch.message.panel_on" : "kitwatch.message.panel_off"), false);
			}
		});

		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(MOD_ID, "panel"), new KitWatchHud(config, monitor));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
				ClientCommands.literal(MOD_ID)
						.then(toggle("panel", value -> config.panel = value))
						.then(toggle("sounds", value -> config.sounds = value))
						.then(toggle("totemguard", value -> config.totemGuard = value))
						.then(toggle("showempty", value -> config.showEmpty = value))
						.then(ClientCommands.literal("side")
								.then(ClientCommands.literal("left").executes(context -> set(context.getSource(), "side", () -> config.side = "left", "left")))
								.then(ClientCommands.literal("right").executes(context -> set(context.getSource(), "side", () -> config.side = "right", "right"))))
						.then(number("armor", 1, 100, value -> config.armorWarnPercent = value))
						.then(number("effects", 1, 120, value -> config.effectWarnSeconds = value))
						.then(ClientCommands.literal("low")
								.then(ClientCommands.argument("supply", StringArgumentType.word())
										.suggests((context, builder) -> {
											for (Supply supply : Supply.values()) {
												builder.suggest(supply.id());
											}

											return builder.buildFuture();
										})
										.then(ClientCommands.argument("count", IntegerArgumentType.integer(0, 2304)).executes(context -> {
											String id = StringArgumentType.getString(context, "supply");
											Supply supply = Supply.byId(id);

											if (supply == null) {
												String known = Arrays.stream(Supply.values()).map(Supply::id).collect(Collectors.joining(", "));
												context.getSource().sendError(Component.translatable("kitwatch.message.unknown_supply", id, known));
												return 0;
											}

											int count = IntegerArgumentType.getInteger(context, "count");
											return set(context.getSource(), "low " + supply.id(), () -> config.lowStock.put(supply.id(), count), String.valueOf(count));
										}))))));
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
		source.sendFeedback(Component.translatable("kitwatch.message.setting", name, shown));
		return 1;
	}
}

package io.github.yarden2012.threatpings;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import org.lwjgl.glfw.GLFW;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

public class ThreatPingsClient implements ClientModInitializer {
	public static final String MOD_ID = "threatpings";

	private static ThreatPingsConfig config;
	private static PingTracker tracker;
	private static KeyMapping toggleKey;
	private static boolean listenerAdded;

	@Override
	public void onInitializeClient() {
		config = ThreatPingsConfig.load();
		tracker = new PingTracker(config);

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + MOD_ID + ".toggle", GLFW.GLFW_KEY_U, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// The sound manager isn't ready yet while mods initialize, so hook it on the first tick.
			if (!listenerAdded) {
				client.getSoundManager().addListener(tracker);
				listenerAdded = true;
			}

			while (toggleKey.consumeClick()) {
				setEnabled(client, !config.enabled);
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> tracker.clear());

		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Identifier.fromNamespaceAndPath(MOD_ID, "ring"), new ThreatPingsHud(config, tracker));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
				ClientCommands.literal(MOD_ID)
						.then(ClientCommands.literal("on").executes(context -> setEnabled(context.getSource().getClient(), true)))
						.then(ClientCommands.literal("off").executes(context -> setEnabled(context.getSource().getClient(), false)))
						.then(ClientCommands.literal("kinds").executes(context -> listKinds(context.getSource())))
						.then(kindToggle())
						.then(ClientCommands.literal("range")
								.then(ClientCommands.argument("blocks", IntegerArgumentType.integer(4, 128)).executes(context -> {
									config.maxDistance = IntegerArgumentType.getInteger(context, "blocks");
									return saved(context.getSource(), "range", config.maxDistance + " blocks");
								})))
						.then(ClientCommands.literal("duration")
								.then(ClientCommands.argument("seconds", FloatArgumentType.floatArg(0.5F, 15.0F)).executes(context -> {
									config.durationSeconds = FloatArgumentType.getFloat(context, "seconds");
									return saved(context.getSource(), "duration", config.durationSeconds + "s");
								})))
						.then(ClientCommands.literal("ring")
								.then(ClientCommands.argument("pixels", IntegerArgumentType.integer(16, 160)).executes(context -> {
									config.ringRadius = IntegerArgumentType.getInteger(context, "pixels");
									return saved(context.getSource(), "ring", config.ringRadius + "px");
								})))
						.then(ClientCommands.literal("distance")
								.then(ClientCommands.literal("on").executes(context -> {
									config.showDistance = true;
									return saved(context.getSource(), "distance", "on");
								}))
								.then(ClientCommands.literal("off").executes(context -> {
									config.showDistance = false;
									return saved(context.getSource(), "distance", "off");
								})))));
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> kindToggle() {
		return ClientCommands.literal("kind")
				.then(ClientCommands.argument("kind", StringArgumentType.word())
						.suggests((context, builder) -> {
							for (ThreatKind kind : ThreatKind.values()) {
								builder.suggest(kind.id());
							}

							return builder.buildFuture();
						})
						.then(ClientCommands.literal("on").executes(context -> setKind(context.getSource(), StringArgumentType.getString(context, "kind"), true)))
						.then(ClientCommands.literal("off").executes(context -> setKind(context.getSource(), StringArgumentType.getString(context, "kind"), false))));
	}

	private static int setKind(FabricClientCommandSource source, String id, boolean enabled) {
		ThreatKind kind = ThreatKind.byId(id);

		if (kind == null) {
			source.sendError(Component.translatable("threatpings.message.unknown_kind", id));
			return 0;
		}

		if (enabled) {
			config.disabledKinds.remove(kind.id());
		} else {
			config.disabledKinds.add(kind.id());
		}

		return saved(source, kind.id(), enabled ? "on" : "off");
	}

	private static int listKinds(FabricClientCommandSource source) {
		MutableComponent list = Component.empty();

		for (ThreatKind kind : ThreatKind.values()) {
			if (kind.ordinal() > 0) {
				list.append(" ");
			}

			list.append(Component.literal(kind.id()).withStyle(config.isEnabled(kind) ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
		}

		source.sendFeedback(Component.translatable("threatpings.message.kinds", list));
		return 1;
	}

	private static int setEnabled(Minecraft client, boolean enabled) {
		config.enabled = enabled;
		config.save();

		if (!enabled) {
			tracker.clear();
		}

		client.gui.setOverlayMessage(Component.translatable(enabled ? "threatpings.message.on" : "threatpings.message.off"), false);
		return 1;
	}

	private static int saved(FabricClientCommandSource source, String name, String shown) {
		config.save();
		source.sendFeedback(Component.translatable("threatpings.message.setting", name, shown));
		return 1;
	}
}

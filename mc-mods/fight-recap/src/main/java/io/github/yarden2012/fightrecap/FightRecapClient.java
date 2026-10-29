package io.github.yarden2012.fightrecap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;

public class FightRecapClient implements ClientModInitializer {
	public static final String MOD_ID = "fightrecap";
	private static final int HISTORY_SIZE = 10;

	private static FightRecapConfig config;
	private static FightTracker tracker;
	/** Most recent fight first. */
	private static final Deque<Fight> history = new ArrayDeque<>();
	private static int sessionFights;
	private static int sessionWins;
	private static int sessionLosses;
	private static KeyMapping lastKey;
	private static boolean soundListenerAdded;

	@Override
	public void onInitializeClient() {
		config = FightRecapConfig.load();
		tracker = new FightTracker(config, FightRecapClient::onFightFinished);

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		lastKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + MOD_ID + ".last", GLFW.GLFW_KEY_N, category));

		AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			// The integrated server fires this too; only track the client's own swing.
			if (level.isClientSide() && player == Minecraft.getInstance().player) {
				tracker.onAttack(player, entity);
			}

			return InteractionResult.PASS;
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// The sound manager isn't ready yet while mods initialize, so hook it on the first tick.
			if (!soundListenerAdded) {
				client.getSoundManager().addListener((sound, events, range) -> tracker.onSound(sound.getIdentifier(), sound.getX(), sound.getY(), sound.getZ()));
				soundListenerAdded = true;
			}

			tracker.tick(client);

			while (lastKey.consumeClick()) {
				printLast(client);
			}
		});

		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Identifier.fromNamespaceAndPath(MOD_ID, "live"), new FightRecapHud(config, tracker));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
				ClientCommands.literal(MOD_ID)
						.executes(context -> printLast(context.getSource().getClient()))
						.then(ClientCommands.literal("last").executes(context -> printLast(context.getSource().getClient())))
						.then(ClientCommands.literal("history").executes(context -> printHistory(context.getSource())))
						.then(ClientCommands.literal("show")
								.then(ClientCommands.argument("number", IntegerArgumentType.integer(1, HISTORY_SIZE)).executes(context ->
										show(context.getSource(), IntegerArgumentType.getInteger(context, "number")))))
						.then(ClientCommands.literal("stats").executes(context -> printStats(context.getSource())))
						.then(toggle("auto", value -> config.autoRecap = value))
						.then(toggle("log", value -> config.csvLog = value))
						.then(toggle("live", value -> config.liveHud = value))
						.then(toggle("playersonly", value -> config.playersOnly = value))
						.then(ClientCommands.literal("timeout")
								.then(ClientCommands.argument("seconds", IntegerArgumentType.integer(3, 120)).executes(context -> {
									config.timeoutSeconds = IntegerArgumentType.getInteger(context, "seconds");
									config.save();
									context.getSource().sendFeedback(Component.translatable("fightrecap.message.setting", "timeout", config.timeoutSeconds + "s"));
									return 1;
								})))));
	}

	private static void onFightFinished(Fight fight) {
		history.addFirst(fight);
		sessionFights++;

		if (fight.outcome == Fight.Outcome.WIN) {
			sessionWins++;
		} else if (fight.outcome == Fight.Outcome.LOSS) {
			sessionLosses++;
		}

		while (history.size() > HISTORY_SIZE) {
			history.removeLast();
		}

		Minecraft client = Minecraft.getInstance();

		if (config.csvLog && !FightLog.append(fight) && client.player != null) {
			client.gui.getChat().addClientSystemMessage(Component.translatable("fightrecap.message.log_failed"));
		}

		if (config.autoRecap && client.player != null) {
			print(client, fight);
		}
	}

	private static void print(Minecraft client, Fight fight) {
		for (Component line : RecapPrinter.lines(fight)) {
			client.gui.getChat().addClientSystemMessage(line);
		}
	}

	private static int printLast(Minecraft client) {
		if (history.isEmpty()) {
			client.gui.getChat().addClientSystemMessage(Component.translatable("fightrecap.history.empty"));
			return 0;
		}

		print(client, history.getFirst());
		return 1;
	}

	private static int printHistory(FabricClientCommandSource source) {
		if (history.isEmpty()) {
			source.sendFeedback(Component.translatable("fightrecap.history.empty"));
			return 0;
		}

		int number = 1;

		for (Fight fight : history) {
			source.sendFeedback(RecapPrinter.historyLine(number++, fight));
		}

		return history.size();
	}

	private static int show(FabricClientCommandSource source, int number) {
		List<Fight> fights = new ArrayList<>(history);

		if (number > fights.size()) {
			source.sendError(Component.translatable("fightrecap.history.missing", number, fights.size()));
			return 0;
		}

		print(source.getClient(), fights.get(number - 1));
		return 1;
	}

	private static int printStats(FabricClientCommandSource source) {
		source.sendFeedback(Component.translatable("fightrecap.stats", sessionFights, sessionWins, sessionLosses));
		return 1;
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> toggle(String name, Consumer<Boolean> setter) {
		return ClientCommands.literal(name)
				.then(ClientCommands.literal("on").executes(context -> set(context.getSource(), name, setter, true)))
				.then(ClientCommands.literal("off").executes(context -> set(context.getSource(), name, setter, false)));
	}

	private static int set(FabricClientCommandSource source, String name, Consumer<Boolean> setter, boolean value) {
		setter.accept(value);
		config.save();
		source.sendFeedback(Component.translatable("fightrecap.message.setting", name, value ? "on" : "off"));
		return 1;
	}
}

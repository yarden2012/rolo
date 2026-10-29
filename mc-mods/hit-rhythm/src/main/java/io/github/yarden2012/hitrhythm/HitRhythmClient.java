package io.github.yarden2012.hitrhythm;

import java.util.function.Consumer;

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

public class HitRhythmClient implements ClientModInitializer {
	public static final String MOD_ID = "hitrhythm";

	private static HitRhythmConfig config;
	private static HitTracker tracker;
	private static KeyMapping panelKey;

	@Override
	public void onInitializeClient() {
		config = HitRhythmConfig.load();
		tracker = new HitTracker(config);

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		panelKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + MOD_ID + ".panel", GLFW.GLFW_KEY_H, category));

		AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			// The integrated server fires this too; only grade the client's own swing.
			if (level.isClientSide() && player == Minecraft.getInstance().player) {
				tracker.onAttack(player, entity);
			}

			return InteractionResult.PASS;
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			tracker.tick(client);

			while (panelKey.consumeClick()) {
				config.panel = !config.panel;
				config.save();
				client.gui.setOverlayMessage(Component.translatable(config.panel ? "hitrhythm.message.panel_on" : "hitrhythm.message.panel_off"), false);
			}
		});

		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Identifier.fromNamespaceAndPath(MOD_ID, "hud"), new HitRhythmHud(config, tracker));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
				ClientCommands.literal(MOD_ID)
						.executes(context -> stats(context.getSource()))
						.then(ClientCommands.literal("stats").executes(context -> stats(context.getSource())))
						.then(ClientCommands.literal("reset").executes(context -> {
							tracker.reset();
							context.getSource().sendFeedback(Component.translatable("hitrhythm.message.reset"));
							return 1;
						}))
						.then(toggle("cue", value -> config.readyCue = value))
						.then(toggle("meter", value -> config.meter = value))
						.then(toggle("popup", value -> config.gradePopup = value))
						.then(toggle("panel", value -> config.panel = value))
						.then(toggle("playersonly", value -> config.playersOnly = value))));
	}

	private static int stats(FabricClientCommandSource source) {
		if (tracker.hits() == 0) {
			source.sendFeedback(Component.translatable("hitrhythm.message.no_hits"));
			return 0;
		}

		source.sendFeedback(Component.translatable("hitrhythm.panel.hits", tracker.hits(), tracker.averageChargePercent()));
		source.sendFeedback(Component.translatable("hitrhythm.panel.clean", tracker.cleanPercent(), tracker.perfectPercent()));
		source.sendFeedback(Component.translatable("hitrhythm.panel.bonus", tracker.crits(), tracker.knockbackHits()));
		source.sendFeedback(Component.translatable("hitrhythm.panel.streak", tracker.cleanStreak(), tracker.bestCleanStreak()));
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
		source.sendFeedback(Component.translatable("hitrhythm.message.setting", name, value ? "on" : "off"));
		return 1;
	}
}

package io.github.yarden2012.pvpsmoketest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Boots the client with all five PvP mods, stands the player on a pillar high above the
 * ground (void on every side for Ledge Sense), hands out a PvP kit (Kit Watch), lands three
 * full-charge sword hits on a pig (Hit Rhythm and Fight Recap), takes a hit back, plays combat
 * sounds around the player (Threat Pings), pops the offhand totem (Kit Watch's RE-TOTEM), then
 * kills the pig and checks the row Fight Recap logged for the fight. Screenshots of each step
 * end up in build/run/clientGameTest/screenshots.
 */
@SuppressWarnings("UnstableApiUsage")
public class PvpModsClientGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("pvpsmoketest");
	private static final List<String> MODS = List.of("hitrhythm", "ledgesense", "kitwatch", "fightrecap", "threatpings");

	@Override
	public void runTest(ClientGameTestContext context) {
		for (String id : MODS) {
			if (!FabricLoader.getInstance().isModLoaded(id)) {
				throw new AssertionError(id + " is not loaded");
			}
		}

		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			TestServerContext server = singleplayer.getServer();
			singleplayer.getClientLevel().waitForChunksRender();

			// Player on a single block, pig on a walled platform two blocks south. The glass
			// behind the player stops the pig's hit from knocking them off the pillar.
			server.runCommand("gamemode survival @a");
			server.runCommand("setblock 0 150 0 minecraft:stone");
			server.runCommand("fill 0 151 -1 0 152 -1 minecraft:glass");
			server.runCommand("fill -1 150 2 1 150 4 minecraft:stone");
			server.runCommand("fill -1 151 4 1 152 4 minecraft:glass");
			server.runCommand("tp @a 0.5 151 0.5 0 10");
			server.runCommand("summon minecraft:pig 0.5 151 2.5 {NoAI:1b}");
			// Resistance IV cuts each sword hit to a sliver, so the pig survives until we kill it.
			server.runCommand("effect give @e[type=minecraft:pig] minecraft:resistance 1000 3 true");

			// A PvP kit, a half-broken chestplate and a buff that's about to run out.
			server.runCommand("item replace entity @a weapon.mainhand with minecraft:diamond_sword");
			server.runCommand("item replace entity @a weapon.offhand with minecraft:totem_of_undying");
			server.runCommand("give @a minecraft:totem_of_undying 2");
			server.runCommand("give @a minecraft:ender_pearl 3");
			server.runCommand("give @a minecraft:golden_apple 5");
			server.runCommand("give @a minecraft:arrow 16");
			server.runCommand("give @a minecraft:splash_potion[minecraft:potion_contents={potion:\"minecraft:healing\"}] 2");
			server.runCommand("item replace entity @a armor.head with minecraft:diamond_helmet");
			server.runCommand("item replace entity @a armor.chest with minecraft:diamond_chestplate[minecraft:damage=500]");
			server.runCommand("effect give @a minecraft:strength 8 0");
			context.waitTicks(20);

			// Each mod's client commands, sent the same way typing them in chat does.
			clientCommand(context, "fightrecap playersonly off");
			clientCommand(context, "hitrhythm panel on");
			clientCommand(context, "kitwatch showempty on");
			clientCommand(context, "ledgesense radius 3");
			clientCommand(context, "threatpings kind eat on");
			context.waitTicks(5);

			// Three full-charge hits, spaced out past the pig's invulnerability frames.
			for (int i = 0; i < 3; i++) {
				context.waitTicks(16);
				context.runOnClient(client -> {
					LivingEntity pig = client.level.getEntitiesOfClass(LivingEntity.class, client.player.getBoundingBox().inflate(6),
							entity -> entity.getType() == EntityType.PIG).stream().findFirst()
							.orElseThrow(() -> new AssertionError("No pig to hit"));
					client.gameMode.attack(client.player, pig);
				});
			}

			// Get hit back by the pig and hear a fight going on around us.
			server.runCommand("damage @p 3 minecraft:mob_attack by @e[type=minecraft:pig,limit=1]");
			server.runCommand("execute at @a run playsound minecraft:entity.ender_pearl.throw neutral @a ~6 ~ ~");
			server.runCommand("execute at @a run playsound minecraft:item.totem.use player @a ~-5 ~ ~-4");
			server.runCommand("execute at @a run playsound minecraft:entity.generic.eat player @a ~ ~1 ~-7");
			context.waitTicks(6);
			context.takeScreenshot("pvp-mods-in-combat");

			// A lethal hit pops the offhand totem; with spares left, Kit Watch should shout RE-TOTEM.
			server.runCommand("damage @p 100 minecraft:generic");
			context.waitTicks(4);
			context.takeScreenshot("pvp-mods-retotem");

			// A kill ends the fight as a win; Fight Recap prints it and logs it.
			server.runCommand("kill @e[type=minecraft:pig]");
			context.waitTicks(30);
			context.takeScreenshot("pvp-mods-recap");

			String log = readFightLog();
			LOGGER.info("fightrecap/fights.csv after the test:\n{}", log);
			checkFightRow(log);

			for (String id : MODS) {
				if (!Files.exists(FabricLoader.getInstance().getConfigDir().resolve(id + ".json"))) {
					throw new AssertionError(id + " didn't write its config file");
				}
			}
		}
	}

	/** Checks Fight Recap's CSV row against what the test did. Column order is in FightLog's header. */
	private static void checkFightRow(String log) {
		List<String> rows = log.lines().skip(1).toList();

		if (rows.size() != 1) {
			throw new AssertionError("Expected exactly one fight in the log, got " + rows.size());
		}

		String[] columns = rows.getFirst().split(",");
		expect(columns[1], "WIN", "outcome");
		expect(columns[2], "\"Pig\"", "opponents");
		expect(columns[4], "3", "swings");
		expect(columns[9], "1", "hits taken");
		expect(columns[16], "1", "own totem pops");
		expect(columns[18], "1", "kills");

		// Every swing should register, but give a laggy CI server some slack.
		if (Integer.parseInt(columns[5]) < 2) {
			throw new AssertionError("Only " + columns[5] + " of 3 hits registered");
		}
	}

	private static void expect(String actual, String expected, String what) {
		if (!actual.equals(expected)) {
			throw new AssertionError("Fight Recap logged " + what + " = " + actual + ", expected " + expected);
		}
	}

	private static void clientCommand(ClientGameTestContext context, String command) {
		context.runOnClient(client -> client.getConnection().sendCommand(command));
	}

	private static String readFightLog() {
		Path file = FabricLoader.getInstance().getGameDir().resolve("fightrecap").resolve("fights.csv");

		try {
			return Files.readString(file);
		} catch (IOException e) {
			throw new AssertionError("Fight Recap didn't write " + file, e);
		}
	}
}

# PvP mods smoke test

Not a mod to install: this is a [Fabric client game test](https://docs.fabricmc.net/develop/automatic-testing)
that boots a real Minecraft 26.1.2 client with all five PvP mods loaded and plays through a
short scripted fight:

1. Stands the player on a one-block pillar high above the ground, so there's void on every side
   (Ledge Sense).
2. Hands out a PvP kit, a badly damaged chestplate and a Strength effect that's about to run out
   (Kit Watch).
3. Runs one client command from each mod, the same way typing it in chat would.
4. Lands three full-charge sword hits on a pig (Hit Rhythm, Fight Recap).
5. Gets hit back by the pig next to the void, and hears combat sounds around it (Ledge Sense alarm,
   Threat Pings).
6. Takes a lethal hit that pops the offhand totem (Kit Watch's RE-TOTEM).
7. Kills the pig and checks the row Fight Recap logged for the fight: a win against the pig, 3 swings,
   at least 2 confirmed hits, 1 hit taken, 1 totem popped, 1 kill. Also checks that every mod
   wrote its config file.

CI runs it after the mods build (the `smoke-test` job in `.github/workflows/build-mod.yml`) and
uploads the screenshots as the `smoke-test-screenshots` artifact.

To run it locally, copy the five mod jars into `mods/` and run `./gradlew runClientGameTest`.

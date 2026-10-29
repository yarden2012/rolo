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
5. Takes damage and plays combat sounds around the player (Ledge Sense alarm, Threat Pings).
6. Removes the offhand totem (Kit Watch's RE-TOTEM).
7. Kills the pig and checks that Fight Recap logged the fight as a win, and that every mod wrote
   its config file.

CI runs it after the mods build (the `smoke-test` job in `.github/workflows/build-mod.yml`) and
uploads the screenshots as the `smoke-test-screenshots` artifact.

To run it locally, copy the five mod jars into `mods/` and run `./gradlew runClientGameTest`.

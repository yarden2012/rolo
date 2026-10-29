# Hit Rhythm

A client-side Fabric mod for Minecraft Java Edition **26.1.2** that coaches your attack
timing in 1.9+ combat.

Since 1.9, how much damage a hit does depends on how charged your weapon is
(`0.2 + 0.8 × charge²`), and only hits above **90% charge** can crit or deal sprint
knockback. Spam-clicking feels fast but throws most of that away. Hit Rhythm makes the
timing audible and visible so you can build the rhythm into muscle memory.

## Features

- **Ready cue** – a soft tick plays the instant your weapon is fully charged. It only
  plays while you're in combat (you hit something or got hit in the last 8 seconds),
  so it stays quiet while you build or mine.
- **Charge meter** – a thin bar under the crosshair during combat, red → yellow → green,
  with a white notch at the 90% crit/knockback threshold.
- **Hit grades** – every hit you land pops up a grade above the crosshair:
  - `PERFECT` – 100% charged
  - `GOOD` – over 90%: full crit/knockback eligibility, a little damage lost
  - `EARLY` – 90% or less, showing the share of damage the hit actually dealt
    (`EARLY (51% dmg)`)
  
  plus `+CRIT` for a critical hit and `+KB` for a sprint-knockback hit.
- **Session stats** – hits graded, average charge, clean (>90%) and perfect percentages,
  crits, knockback hits and your clean-hit streak.

## Controls

| Key / command | What it does |
| --- | --- |
| **H** (rebindable) | Show/hide the stats panel |
| `/hitrhythm` or `/hitrhythm stats` | Print your session stats in chat |
| `/hitrhythm reset` | Reset the session stats |
| `/hitrhythm cue on\|off` | Ready cue |
| `/hitrhythm meter on\|off` | Charge meter |
| `/hitrhythm popup on\|off` | Hit grade popup |
| `/hitrhythm panel on\|off` | Stats panel |
| `/hitrhythm playersonly on\|off` | Grade only hits on players (default: mobs count too, so you can practice on them) |

Settings are saved to `config/hitrhythm.json`, which also holds the cue volume/pitch
and the length of the combat window.

## Building

Requires Java 25. From this directory:

```
./gradlew build
```

The mod jar ends up in `build/libs/hit-rhythm-1.0.0.jar`. CI also builds it on every push
(see `.github/workflows/build-mod.yml`) and uploads the jar as a workflow artifact.

## Installing

1. Install the [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.1.2.
2. Drop these into your `mods` folder:
   - [Fabric API](https://modrinth.com/mod/fabric-api) (`0.155.x+26.1.2`)
   - `hit-rhythm-1.0.0.jar`

It's client-side only: it works on any server, including vanilla ones, and doesn't
need to be installed on the server.

## Versions used

| Component     | Version          |
| ------------- | ---------------- |
| Minecraft     | 26.1.2           |
| Fabric Loader | 0.20.1           |
| Fabric API    | 0.155.3+26.1.2   |
| Fabric Loom   | 1.19-SNAPSHOT    |
| Gradle        | 9.8.2 (wrapper)  |
| Java          | 25               |

# Threat Pings

A client-side Fabric mod for Minecraft Java Edition **26.1.2** that lets you *see*
combat sounds.

You can hear an enemy throw a pearl, eat a gapple or pop a totem, but in a busy fight
it's easy to miss which direction it came from. Threat Pings puts each combat sound on a
ring around your crosshair, at the angle it came from: the top of the ring is in front of
you and the bottom is behind. Each ping has a label, a distance, and ▲/▼ if the sound was
well above or below you. Pings fade after 3 seconds.

| Ping | Sound |
| --- | --- |
| `PEARL` | Ender pearl thrown |
| `BOW` | Arrow fired |
| `XBOW` | Crossbow fired or finished loading |
| `TRIDENT` | Trident thrown |
| `SPLASH` | Splash or lingering potion thrown |
| `DRINK` / `EAT` | Someone drinking a potion / eating (usually a gapple mid-fight) |
| `TOTEM` | Totem of Undying popped |
| `SHIELD` / `SHIELD BROKE` | Shield blocked a hit / was disabled |
| `WIND` | Wind charge thrown |
| `MACE` | Mace smash |
| `BOOM` / `TNT` / `ANCHOR` | Explosion (crystals, beds…) / TNT lit / respawn anchor charged |
| `ROCKET` | Firework launched (elytra boost or crossbow) |

## Fair play

Threat Pings only shows sounds your client is already playing. A sound only becomes a
ping if it's within its own hearing range, the same check vanilla's subtitles use. It's
basically a directional, combat-focused version of the subtitles accessibility option,
and it doesn't reveal anything you couldn't hear with good headphones. Your own sounds
(within 2 blocks of you) are skipped.

## Controls

| Key / command | What it does |
| --- | --- |
| **U** (rebindable) | Turn Threat Pings on/off |
| `/threatpings on\|off` | Same as the key |
| `/threatpings kinds` | List all ping kinds and whether each is on |
| `/threatpings kind <kind> on\|off` | Turn one kind on or off (e.g. `/threatpings kind eat off`) |
| `/threatpings range <blocks>` | Ignore sounds further away than this (default 48) |
| `/threatpings duration <seconds>` | How long a ping stays up (default 3) |
| `/threatpings ring <pixels>` | Ring size (default 60) |
| `/threatpings distance on\|off` | Show distance on the labels |

Settings are saved to `config/threatpings.json`.

## Building

Requires Java 25. From this directory:

```
./gradlew build
```

The mod jar ends up in `build/libs/threat-pings-1.0.0.jar`. CI also builds it on every push
(see `.github/workflows/build-mod.yml`) and uploads the jar as a workflow artifact.

## Installing

1. Install the [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.1.2.
2. Drop these into your `mods` folder:
   - [Fabric API](https://modrinth.com/mod/fabric-api) (`0.155.x+26.1.2`)
   - `threat-pings-1.0.0.jar`

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

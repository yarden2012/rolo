# Ledge Sense

A client-side Fabric mod for Minecraft Java Edition **26.1.2** that stops you getting
comboed off cliffs, bridges and islands.

In a fight you're looking at your opponent, not at the ground behind you. Ledge Sense
keeps checking the ground around you and draws a small ring of arrows around your
crosshair. Each arrow points toward a dangerous edge, and its color shows how bad the
fall would be.

## Features

- Looks out up to 3 blocks (adjustable, 1–6) in eight directions, roughly the distance
  one or two knockback hits push you.
- Arrow colors:
  - **Yellow** – a drop that will hurt
  - **Orange** – a drop that would take half your current health or more
  - **Red** – void, lava, or a fall that would kill you outright
- Fall damage is worked out from your real **safe fall distance** and **fall damage
  multiplier** attributes, so it follows your gear and effects.
- Landing in water counts as safe. A wall two blocks tall stops the scan in that
  direction, because knockback can't carry you through it.
- The most dangerous direction gets a label: `VOID 2m`, `LAVA 1m`, `DROP 14`.
- **Hit alarm** – if you take a hit with a deadly edge within 2 blocks, a low warning
  plays. If the edge is behind you, the label under the ring flashes `EDGE BEHIND YOU`.
- The ring fades while you sneak, since sneaking already stops you walking off edges. It
  hides in creative and spectator, while flying, gliding or riding, and while falling.

## Controls

| Key / command | What it does |
| --- | --- |
| **J** (rebindable) | Turn Ledge Sense on/off |
| `/ledgesense on\|off` | Same as the key |
| `/ledgesense radius <1-6>` | How far out to look, in blocks |
| `/ledgesense mindrop <2-64>` | Ignore drops shallower than this |
| `/ledgesense ring <12-120>` | Ring size in GUI pixels |
| `/ledgesense labels on\|off` | Text label on the worst arrow |
| `/ledgesense alarm on\|off` | Hit alarm sound |
| `/ledgesense dimsneak on\|off` | Fade the ring while sneaking |

Settings are saved to `config/ledgesense.json`.

## Building

Requires Java 25. From this directory:

```
./gradlew build
```

The mod jar ends up in `build/libs/ledge-sense-1.0.0.jar`. CI also builds it on every push
(see `.github/workflows/build-mod.yml`) and uploads the jar as a workflow artifact.

## Installing

1. Install the [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.1.2.
2. Drop these into your `mods` folder:
   - [Fabric API](https://modrinth.com/mod/fabric-api) (`0.155.x+26.1.2`)
   - `ledge-sense-1.0.0.jar`

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

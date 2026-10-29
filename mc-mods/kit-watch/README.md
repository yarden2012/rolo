# Kit Watch

A client-side Fabric mod for Minecraft Java Edition **26.1.2** that keeps track of your
PvP kit, so you never open your inventory mid-fight to count pearls.

## Features

- **Supply column** – a compact column on the left edge of the screen (or the right)
  showing how many of each you're carrying:
  totems, golden apples (normal + enchanted), ender pearls, wind charges, arrows
  (normal, spectral and tipped), and healing potions (any drinkable, splash or lingering
  potion with Instant Health).
- **Low-stock alerts** – when a supply drops to its low mark (for example your last 2
  pearls, or your last totem) the row flashes red, a short message appears and a chime
  plays. A second alert fires when you run out completely. Alerts only fire when a count
  *drops* past the mark, never repeatedly.
- **Armor durability** – every worn armor piece with its remaining durability, colored
  green / yellow / orange / red. An alert fires at 20% and again at 5%.
- **Buff countdown** – beneficial effects (Strength, Speed, Fire Resistance…) get a
  countdown in the column during their last 10 seconds. A chime plays when the countdown
  starts and another when the effect runs out. Beacon and conduit effects are ignored,
  because they're constantly refreshed.
- **Totem guard** – the moment your offhand totem pops while you still carry spares, a
  big **RE-TOTEM!** pulsing yellow and red appears until you have one back in your offhand. If that was
  your last one, you get **NO TOTEMS LEFT** instead.

## Controls

| Key / command | What it does |
| --- | --- |
| **K** (rebindable) | Show/hide the column (alerts keep working) |
| `/kitwatch panel on\|off` | Same as the key |
| `/kitwatch side left\|right` | Which screen edge the column sits on |
| `/kitwatch showempty on\|off` | Keep supplies you've run out of in the column |
| `/kitwatch sounds on\|off` | Alert sounds |
| `/kitwatch totemguard on\|off` | RE-TOTEM warning |
| `/kitwatch armor <percent>` | Armor alert threshold (default 20) |
| `/kitwatch effects <seconds>` | When the buff countdown starts (default 10) |
| `/kitwatch low <supply> <count>` | Low-stock mark for one supply: `totems`, `gapples`, `pearls`, `wind_charges`, `arrows`, `healing` |

Settings are saved to `config/kitwatch.json`.

## Building

Requires Java 25. From this directory:

```
./gradlew build
```

The mod jar ends up in `build/libs/kit-watch-1.0.0.jar`. CI also builds it on every push
(see `.github/workflows/build-mod.yml`) and uploads the jar as a workflow artifact.

## Installing

1. Install the [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.1.2.
2. Drop these into your `mods` folder:
   - [Fabric API](https://modrinth.com/mod/fabric-api) (`0.155.x+26.1.2`)
   - `kit-watch-1.0.0.jar`

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

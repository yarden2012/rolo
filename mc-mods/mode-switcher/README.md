# Mode Switcher

A client-side Fabric mod for Minecraft Java Edition **1.21.11** that switches your
in-game settings between two profiles — **Vanilla** and **PvP** — with a single key press.

## How it works

- Press **G** (rebindable in *Options → Controls → Key Binds*) to toggle between the
  Vanilla and PvP profiles. An action-bar message confirms the switch and how many
  settings were applied.
- Which settings get switched is up to you: open the mod's config screen from
  **Mod Menu** (or bind the "Open Mode Switcher Config" key) and tick the settings
  you want the toggle to change.
- To set up a profile: adjust your settings normally in the vanilla Options screens,
  then open the config screen and click **Save current as Vanilla** or
  **Save current as PvP**. The stored values for both profiles are shown next to each
  setting (`V:` = Vanilla, `P:` = PvP).

### Switchable settings

FOV, Mouse Sensitivity, Brightness, Render Distance, Max Framerate, Auto-Jump,
View Bobbing, FOV Effects, Distortion Effects, Particles, Clouds.

Everything is stored in `config/modeswitcher.json`.

## Building

Requires Java 21 (or newer). From this directory:

```
./gradlew build
```

The mod jar ends up in `build/libs/mode-switcher-1.0.0.jar`.

## Installing

1. Install the [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.11.
2. Drop these into your `mods` folder:
   - [Fabric API](https://modrinth.com/mod/fabric-api) (`0.141.x+1.21.11`)
   - [Mod Menu](https://modrinth.com/mod/modmenu) (`17.x`, optional but recommended —
     it's how you open the config screen from the Mods menu)
   - `mode-switcher-1.0.0.jar` from the build output

## Versions used

| Component     | Version          |
| ------------- | ---------------- |
| Minecraft     | 1.21.11          |
| Yarn mappings | 1.21.11+build.6  |
| Fabric Loader | 0.19.3           |
| Fabric API    | 0.141.6+1.21.11  |
| Mod Menu      | 17.0.0           |
| Fabric Loom   | 1.13-SNAPSHOT    |
| Gradle        | 8.14.3 (wrapper) |

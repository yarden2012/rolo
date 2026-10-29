# Fight Recap

A client-side Fabric mod for Minecraft Java Edition **26.1.2** that gives you a
post-match report for every PvP fight.

Fight Recap tracks each fight in the background. When it ends (someone dies, or 12
seconds pass with no hits either way) it prints a recap in chat:

```
Fight Recap: WIN vs Steve (0:42)
Hits 14/22 swings (64%)  Crits 5  Best combo 6
Took 9 hits, 11.8❤ damage  Their best combo 4
Healed 6.0❤  Gapples 1  Pearls 2  Potions 0
Totems popped: you 0, them 1
```

## What gets tracked

- **Swings vs. hits.** A swing only counts as a hit when the server confirms it: the
  target's hurt animation restarts right after your swing and the server names you as
  the damage source. Clicks that land during invulnerability frames and do nothing don't
  count, and neither do a teammate's hits, so the accuracy is honest.
- **Crits**, using vanilla's exact crit rules.
- **Combos both ways:** your longest run of hits without being hit back, and your
  opponent's.
- **Hits taken:** melee or projectile hits from an opponent, using the attacker the
  server reports.
- **Damage taken and health healed**, absorption included, shown in hearts. Fall, fire
  and poison damage during a fight count toward the damage total, but not as hits.
- **Consumables used during the fight:** golden apples, pearls, potions, and your own
  totem pops.
- **Your opponent's totem pops**, picked up from the totem sound at their position.
- **Outcome:** `WIN` if your opponent died, `LOSS` if you did, otherwise `ENDED`.

A small live line next to your hotbar (`8 - 3   combo 4`) shows hits landed, hits taken
and your current combo while the fight runs. It turns green when you're on a 3+ combo
and red when they are.

Every fight is also added to `fightrecap/fights.csv` in your game folder, one row per
fight, so you can chart your accuracy, combos and win rate over time in any spreadsheet.

## Controls

| Key / command | What it does |
| --- | --- |
| **N** (rebindable) | Show the last fight's recap again |
| `/fightrecap` or `/fightrecap last` | Same as the key |
| `/fightrecap history` | One line for each of the last 10 fights |
| `/fightrecap show <n>` | Full recap of fight #n from the history |
| `/fightrecap stats` | Fights, wins and losses this session |
| `/fightrecap auto on\|off` | Print the recap automatically when a fight ends |
| `/fightrecap log on\|off` | Write fights to the CSV file |
| `/fightrecap live on\|off` | Live score line |
| `/fightrecap playersonly on\|off` | Only player fights count (turn off to practice on mobs) |
| `/fightrecap timeout <seconds>` | How long without hits before a fight is over |

Settings are saved to `config/fightrecap.json`. Fights with fewer than 3 hits in total
are too small to report (`minExchanges` in the config).

## Building

Requires Java 25. From this directory:

```
./gradlew build
```

The mod jar ends up in `build/libs/fight-recap-1.0.0.jar`. CI also builds it on every push
(see `.github/workflows/build-mod.yml`) and uploads the jar as a workflow artifact.

## Installing

1. Install the [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.1.2.
2. Drop these into your `mods` folder:
   - [Fabric API](https://modrinth.com/mod/fabric-api) (`0.155.x+26.1.2`)
   - `fight-recap-1.0.0.jar`

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

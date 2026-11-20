# Reflex

An offline reaction-time trainer for Android. No account, no ads, no network:
the app asks for no permissions at all, and your history stays on the phone.

## Drills

| Drill | What you do | What it trains |
|---|---|---|
| **Tap reaction** | The screen turns from dark red to bright green. Tap. | Simple reaction time |
| **Sound reaction** | A beep plays while the screen stays exactly the same. Tap. | Reaction to sound |
| **Go / No-Go** | Tap green circles, hold back on red squares (25% of trials). | Reacting fast *and* stopping yourself |
| **Choice reaction** | One of four pads lights up. Tap that one. | Choice reaction time |
| **Aim** | Targets pop up around the screen. Tap each one. | Reaction plus hand–eye speed |

Every round ends with a results screen: your average, best, median, consistency
(standard deviation), accuracy where it applies, and a bar per trial. You can
compare it with your recent average, and a new personal best is called out. The
**Statistics** screen shows a trend line of your round averages and your recent rounds.
The home screen shows your fastest tap and your day streak.

Settings: round length (short, standard or long) and vibration on tap.

### Fair scoring

- **False starts don't count as reactions.** Tapping before the stimulus is *too soon*.
  Tapping less than 100 ms after it is *too fast*: the stimulus really was there, but nobody
  can react that quickly (the best manage about 150 ms), so the tap began before it. That's
  the same rule sprint starts use, and the screen shows how fast the tap was. Either way the
  trial runs again.
- **Choice taps count for the pad you went for.** Each pad owns its quarter of the grid,
  gaps and a margin included, so a finger landing just beside the lit pad still counts.
  Touches well away from the pads (a palm, the top bar) are ignored rather than scored.
- **Unpredictable waits.** Tap reaction waits 1.2 s plus a random exponential delay, so
  there's no "it must be any moment now" rhythm to count along to.
- **Personal bests must be earned.** A round needs at least 3 valid reactions, and in modes
  with wrong answers at least 80% accuracy. Otherwise tapping everything fast would win Go / No-Go.
- **Colour is never the only cue.** Go / No-Go uses a circle and a square as well as green
  and red, and the Tap reaction signal is a big jump in brightness.

## How timing works

The stimulus is drawn from a `Choreographer` frame callback, so the app knows the vsync time
of the exact frame that shows it. That frame can't reach the screen before the next vsync, so
the onset is taken as *vsync + one refresh period*. The response is the touch event's own
timestamp (`MotionEvent.getEventTimeNanos()` on Android 14+), which is when the touchscreen
reported your finger, not when the app got around to handling it. Both use the same
monotonic clock.

**Sound reaction** has to deal with audio output delay, which ranges from about 10 ms on a
phone speaker to over 200 ms on Bluetooth. Counting that delay as your reaction time would be
unfair. Instead, the app keeps a low-latency audio stream playing silence for the whole round,
writes the beep at a known sample, and uses `AudioTrack.getTimestamp()` to find when that
sample actually reached the output. Your time starts then. Bluetooth headsets don't always
report their extra delay, so the start card warns you when one is connected. It also warns
you if media volume is off. The volume keys control the beep's volume during a round.

Every phone adds some display and touchscreen latency that no app can see, so times can
read a little slower than a lab button press. Compare yourself with yourself, on the same device.

## Install

Grab `reflex.apk` from the **reflex-apk** artifact of the latest
[Reflex Android app](../../actions/workflows/reflex-android.yml) workflow run, or from a
`reflex-v*` release. Open it on your phone and allow installs from that app when Android asks.

Requires Android 7.0 (API 24) or newer.

## Build

With Android Studio, open this folder and run it. From the command line, with JDK 17+ and
the Android SDK:

```sh
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest      # unit + Robolectric tests
```

To publish a release from CI, push a tag such as `reflex-v1.0.0`.

The tests play every drill end to end with scripted players that look only at rendered
pixels and tap with real touch timestamps. They check the timing, scoring, false starts,
timeouts, pausing, and that a finished round is saved.

### Signing

Release builds are signed with `app/debug.keystore`, a committed key with the standard
public debug password (`android`). It's committed on purpose: every build, from CI or any
machine, then has the same signature, so a new APK installs as an update and keeps your
history. It is not a secret. Create your own key before distributing the app anywhere public.

## Layout

```
app/src/main/java/dev/rolo/reflex/
  MainActivity, GameActivity, StatsActivity   screens
  Mode, Session, Stats, HistoryStore, Prefs    model, statistics, storage (one JSON file)
  game/TrainerView                             timing, input, scoring, shared drawing
  game/SimpleView, GoNoGoView, ChoiceView, AimView   (SimpleView runs Tap and Sound reaction)
  game/ToneCue                                 the timed beep (behind AudioCue, so tests can fake it)
  view/TrialBarsView, TrendChartView           charts
```

Plain Java on the Android framework, with no libraries. The APK is under 100 KB.

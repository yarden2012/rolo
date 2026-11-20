package dev.rolo.reflex.game;

import static android.os.Looper.getMainLooper;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.time.Duration;

import dev.rolo.reflex.Mode;
import dev.rolo.reflex.Session;
import dev.rolo.reflex.Ui;

/**
 * Plays every drill end to end with scripted players. The players only look at rendered pixels
 * and tap with real touch timestamps, so these tests cover drawing, timing and scoring together.
 * At mdpi one dp is one pixel.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w360dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DrillTest {
    private static final long STEP_MS = 5;

    private TrainerView view;
    private Session result;

    private TrainerView start(Mode mode, int trials) {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        return start(activity, TrainerView.create(activity, mode, trials));
    }

    private TrainerView start(Activity activity, TrainerView v) {
        view = v;
        view.setHaptics(false);
        view.setListener(new TrainerView.Listener() {
            @Override
            public void onRoundFinished(Session session) {
                result = session;
            }
        });
        activity.setContentView(view);
        idle(50);
        assertTrue("view was laid out", view.getWidth() > 0 && view.getHeight() > 0);
        return view;
    }

    // ------------------------------------------------------------------ Tap reaction

    @Test
    public void simpleMeasuresFromStimulusOnsetToTouchTimestamp() {
        start(Mode.SIMPLE, 3);
        tapCenter(); // intro -> waiting
        for (int i = 0; i < 3; i++) {
            waitFor(new Check() {
                @Override
                public boolean ok() {
                    return pixel(5, view.getHeight() / 2) == Ui.GO;
                }
            }, 6000);
            idle(250);
            tapCenter();
            idle(300); // past the result screen's double-tap guard
            tapCenter(); // next trial, or results after the last
        }
        assertNotNull(result);
        assertEquals(3, result.count());
        for (int i = 0; i < 3; i++) {
            // 250 ms after we saw it, minus the one-frame display allowance (~17 ms),
            // plus however far into a polling step the frame landed.
            int t = result.time(i);
            assertTrue("time " + t, t >= 210 && t <= 250);
        }
        assertEquals(0, result.falseStarts);
        assertEquals(0, result.misses);
    }

    @Test
    public void simpleTapBeforeGreenIsAFalseStartAndRepeatsTheTrial() {
        start(Mode.SIMPLE, 1);
        tapCenter();
        idle(400); // the wait is always at least 1.2 s
        assertFalse(pixel(5, view.getHeight() / 2) == Ui.GO);
        tapCenter(); // too soon
        assertEquals(Ui.WARN_BG, pixel(5, view.getHeight() / 2));
        assertTrue(said().startsWith("Too soon!"));
        idle(300);
        tapCenter(); // try again
        waitFor(green(), 6000);
        idle(300);
        tapCenter();
        idle(300);
        tapCenter();
        assertNotNull(result);
        assertEquals(1, result.falseStarts);
        assertEquals(1, result.count());
    }

    @Test
    public void simpleTapUnder100msAfterGreenCounts() {
        // Any tap after the green is a reaction, however fast.
        start(Mode.SIMPLE, 1);
        tapCenter();
        waitFor(green(), 6000);
        // 60 ms after we saw green; the frame that drew it was up to one refresh (~17 ms)
        // earlier, and the onset is that frame's vsync plus one refresh.
        idle(60);
        tapCenter();
        assertFalse("not a false start", pixel(5, view.getHeight() / 2) == Ui.WARN_BG);
        idle(300);
        tapCenter(); // results
        assertNotNull(result);
        assertEquals(0, result.falseStarts);
        assertEquals(1, result.count());
        assertTrue("time " + result.time(0), result.time(0) >= 40 && result.time(0) <= 65);
    }

    @Test
    public void simpleNoTapIsAMissAfterTimeout() {
        start(Mode.SIMPLE, 1);
        tapCenter();
        waitFor(green(), 6000);
        idle(2600);
        assertFalse(pixel(5, view.getHeight() / 2) == Ui.GO);
        idle(300);
        tapCenter();
        assertNotNull(result);
        assertEquals(1, result.misses);
        assertFalse(result.hasTimes());
    }

    @Test
    public void pausingDropsThePendingStimulus() {
        start(Mode.SIMPLE, 1);
        tapCenter();
        idle(200);
        view.pause();
        idle(8000);
        assertFalse("no stimulus while paused", pixel(5, view.getHeight() / 2) == Ui.GO);
        tapCenter(); // resume
        waitFor(green(), 6000);
        idle(200);
        tapCenter();
        idle(300);
        tapCenter();
        assertNotNull(result);
        assertEquals(1, result.count());
        assertEquals(0, result.falseStarts);
    }

    // ------------------------------------------------------------------ Sound reaction

    /** Stands in for the speaker: each beep is heard a fixed 40 ms after it's requested. */
    private static final class FakeCue implements AudioCue {
        static final long OUTPUT_DELAY_MS = 40;
        long playedAtMs = -1;
        int starts;
        int releases;
        boolean open;

        @Override
        public boolean start() {
            if (!open) starts++;
            open = true;
            return true;
        }

        @Override
        public void play() {
            playedAtMs = SystemClock.uptimeMillis();
        }

        @Override
        public long onsetNanos() {
            return playedAtMs < 0 ? -1 : (playedAtMs + OUTPUT_DELAY_MS) * 1_000_000L;
        }

        @Override
        public void release() {
            if (open) releases++;
            open = false;
        }
    }

    private FakeCue cue;

    private TrainerView startSound(int trials) {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        cue = new FakeCue();
        return start(activity, new SimpleView(activity, Mode.SOUND, trials, cue));
    }

    private void waitForBeep() {
        waitFor(new Check() {
            @Override
            public boolean ok() {
                return cue.playedAtMs >= 0;
            }
        }, 6000);
    }

    @Test
    public void soundTimesFromWhenTheBeepIsHeardAndTheScreenNeverChanges() {
        startSound(3);
        assertTrue("stream opened on the start card", cue.open);
        tapCenter();
        int w = view.getWidth(), h = view.getHeight();
        int[] before = new int[w * h];
        int[] after = new int[w * h];
        for (int i = 0; i < 3; i++) {
            cue.playedAtMs = -1;
            idle(50);
            render().getPixels(before, 0, w, 0, 0, w, h);
            waitForBeep();
            render().getPixels(after, 0, w, 0, 0, w, h);
            assertTrue("the screen must not change when the beep plays", java.util.Arrays.equals(before, after));
            idle(250);
            tapCenter();
            idle(300);
            tapCenter();
        }
        assertNotNull(result);
        assertEquals(3, result.count());
        for (int i = 0; i < 3; i++) {
            // 250 ms after the beep was requested, minus the 40 ms it took to reach the speaker.
            int t = result.time(i);
            assertTrue("time " + t, t >= 205 && t <= 220);
        }
        assertEquals(0, result.falseStarts);
        assertEquals(Mode.SOUND, result.mode);
        assertFalse("stream closed when the round ended", cue.open);
    }

    @Test
    public void soundTapBeforeTheBeepIsAudibleIsAFalseStart() {
        startSound(1);
        tapCenter();
        waitForBeep();
        idle(10); // requested, but still 30 ms from the speaker
        tapCenter();
        assertEquals(Ui.WARN_BG, pixel(5, view.getHeight() / 2));
        assertTrue(said().startsWith("Too soon!"));
        idle(300);
        tapCenter(); // try again
        cue.playedAtMs = -1;
        waitForBeep();
        idle(250);
        tapCenter();
        idle(300);
        tapCenter();
        assertNotNull(result);
        assertEquals(1, result.falseStarts);
        assertEquals(1, result.count());
    }

    @Test
    public void soundTapUnder100msAfterTheBeepCounts() {
        startSound(1);
        tapCenter();
        waitForBeep();
        idle(90); // heard 40 ms in, so this tap comes ~50 ms after the beep
        tapCenter();
        idle(300);
        tapCenter(); // results
        assertNotNull(result);
        assertEquals(0, result.falseStarts);
        assertEquals(1, result.count());
        assertTrue("time " + result.time(0), result.time(0) >= 50 && result.time(0) <= 55);
    }

    @Test
    public void soundClosesTheStreamWhilePausedAndReopensIt() {
        startSound(1);
        tapCenter();
        idle(200);
        view.pause();
        assertFalse(cue.open);
        assertEquals(1, cue.releases);
        idle(6000);
        assertEquals("no beep while paused", -1, cue.playedAtMs);
        tapCenter(); // resume
        assertTrue(cue.open);
        assertEquals(2, cue.starts);
    }

    @Test
    public void beepOnsetMathAndShape() {
        // A frame 0.1 s after a timestamped frame plays 0.1 s after that timestamp, and one
        // 0.1 s before it played 0.1 s earlier.
        assertEquals(1_100_000_000L, ToneCue.onsetFrom(52_800, 48_000, 1_000_000_000L, 48_000));
        assertEquals(900_000_000L, ToneCue.onsetFrom(43_200, 48_000, 1_000_000_000L, 48_000));

        short[] beep = ToneCue.synthesize(48_000);
        assertEquals(48_000 * 120 / 1000, beep.length);
        assertEquals("starts from silence (no click)", 0, beep[0]);
        int peak = 0;
        for (short v : beep) peak = Math.max(peak, Math.abs(v));
        assertTrue("loud enough: " + peak, peak > 20_000);
        assertTrue("no clipping: " + peak, peak <= Math.round(0.8 * Short.MAX_VALUE));
        assertTrue("fades out", Math.abs(beep[beep.length - 1]) < 1000);
    }

    // ------------------------------------------------------------------ Go / No-Go

    @Test
    public void goNoGoCarefulPlayerScoresPerfectly() {
        Session s = playGoNoGo(16, false);
        assertEquals(16, s.correct);
        assertEquals(12, s.count()); // 75% go
        assertEquals(0, s.errors);
        assertEquals(0, s.misses);
        assertEquals(1.0, s.accuracy(), 1e-9);
        assertTrue(s.qualifies());
    }

    @Test
    public void goNoGoTappingEverythingIsPunished() {
        Session s = playGoNoGo(16, true);
        assertEquals(4, s.errors); // every red square
        assertEquals(12, s.correct);
        assertEquals(0.75, s.accuracy(), 1e-9);
        assertFalse(s.qualifies());
    }

    private Session playGoNoGo(int trials, final boolean tapEverything) {
        start(Mode.GO_NO_GO, trials);
        tapCenter();
        final float sx = view.getWidth() / 2f + 50; // inside either shape, clear of the red X
        final float sy = view.getHeight() / 2f;
        while (result == null) {
            waitFor(new Check() {
                @Override
                public boolean ok() {
                    int p = pixel(sx, sy);
                    return result != null || p == Ui.GO || p == Ui.STOP;
                }
            }, 4000);
            if (result != null) break;
            if (pixel(sx, sy) == Ui.GO || tapEverything) {
                idle(220);
                tapCenter();
            } else {
                idle(1100); // hold through the response window
            }
            idle(100);
        }
        return result;
    }

    // ------------------------------------------------------------------ Choice

    @Test
    public void choiceHittingTheLitPadScoresPerfectly() {
        start(Mode.CHOICE, 10);
        tapCenter();
        while (result == null) {
            float[] lit = waitForColor(Ui.CHOICE_LIT, 4000);
            if (lit == null) break;
            idle(300);
            tap(lit[0], lit[1]);
            idle(500);
        }
        assertNotNull(result);
        assertEquals(10, result.count());
        assertEquals(0, result.errors);
        assertEquals(1.0, result.accuracy(), 1e-9);
        for (int i = 0; i < result.count(); i++) {
            int t = result.time(i);
            assertTrue("time " + t, t >= 260 && t <= 300);
        }
    }

    @Test
    public void choiceWrongPadIsAnError() {
        start(Mode.CHOICE, 3);
        tapCenter();
        while (result == null) {
            float[] lit = waitForColor(Ui.CHOICE_LIT, 4000);
            if (lit == null) break;
            idle(300);
            // The other pad in the same row: the grid is centred horizontally.
            tap(view.getWidth() - lit[0], lit[1]);
            idle(500);
        }
        assertNotNull(result);
        assertEquals(3, result.errors);
        assertEquals(0, result.count());
        assertEquals(0.0, result.accuracy(), 1e-9);
    }

    @Test
    public void choiceTapsJustBesideTheLitPadStillCount() {
        // At speed a finger lands a little off target: in the gap between pads, or just past
        // the outer edge. Those taps chose the right pad and must not be scored as wrong.
        start(Mode.CHOICE, 4);
        tapCenter();
        float mid = view.getWidth() / 2f; // the grid is centred horizontally
        for (int trial = 0; trial < 4; trial++) {
            float[] box = waitForBounds(Ui.CHOICE_LIT, 4000);
            boolean left = box[2] < mid;
            float gap = 2 * (left ? mid - box[2] : box[0] - mid);
            boolean top = inTopRow(box, gap);
            float x = (box[0] + box[2]) / 2f;
            float y = (box[1] + box[3]) / 2f;
            switch (trial) {
                case 0: // in the gap beside it, on its side of the middle
                    x = left ? box[2] + 3 : box[0] - 3;
                    break;
                case 1: // just past its outer side edge
                    x = left ? box[0] - 8 : box[2] + 8;
                    break;
                case 2: // in the gap above or below it
                    y = top ? box[3] + 3 : box[1] - 3;
                    break;
                default: // just past its outer top or bottom edge
                    y = top ? box[1] - 8 : box[3] + 8;
                    break;
            }
            idle(300);
            tap(x, y);
            idle(500);
        }
        assertNotNull(result);
        assertEquals("wrong-pad errors", 0, result.errors);
        assertEquals(4, result.count());
    }

    @Test
    public void choiceIgnoresStrayTouchesFarFromThePads() {
        start(Mode.CHOICE, 1);
        tapCenter();
        float[] lit = waitForColor(Ui.CHOICE_LIT, 4000);
        idle(200);
        tap(view.getWidth() / 2f, 10); // a brush on the top bar, nowhere near a pad
        idle(100);
        tap(lit[0], lit[1]);
        idle(500);
        assertNotNull(result);
        assertEquals(0, result.errors);
        assertEquals(1, result.count());
    }

    /** Whether the lit pad is in the top row: an idle pad sits directly below it. */
    private boolean inTopRow(float[] box, float gap) {
        float y = box[3] + gap + 10;
        return y < view.getHeight() && pixel((box[0] + box[2]) / 2f, y) == Ui.SURFACE2;
    }

    // ------------------------------------------------------------------ Aim

    @Test
    public void aimHittingEveryTarget() {
        start(Mode.AIM, 8);
        tapCenter();
        while (result == null) {
            float[] target = waitForColor(Ui.AIM_TARGET, 2000);
            if (target == null) break;
            idle(200);
            tap(target[0], target[1]);
            idle(400); // let the hit ripple fade before looking for the next one
        }
        assertNotNull(result);
        assertEquals(8, result.count());
        assertEquals(0, result.errors);
        assertEquals(1.0, result.accuracy(), 1e-9);
    }

    @Test
    public void aimMissedTapsCount() {
        start(Mode.AIM, 2);
        tapCenter();
        float[] target = waitForColor(Ui.AIM_TARGET, 2000);
        assertNotNull(target);
        // Tap the opposite corner of the play area from the target.
        tap(target[0] < view.getWidth() / 2f ? view.getWidth() - 5 : 5,
                target[1] < view.getHeight() / 2f ? view.getHeight() - 5 : 70);
        idle(100);
        tap(target[0], target[1]);
        idle(400);
        target = waitForColor(Ui.AIM_TARGET, 2000);
        assertNotNull(target);
        tap(target[0], target[1]);
        idle(600);
        assertNotNull(result);
        assertEquals(1, result.errors);
        assertEquals(2, result.count());
        assertEquals(2 / 3.0, result.accuracy(), 1e-9);
    }

    @Test
    public void roundIsNotReportedTwice() {
        start(Mode.SIMPLE, 1);
        tapCenter();
        waitFor(green(), 6000);
        idle(200);
        tapCenter();
        idle(300);
        tapCenter();
        Session first = result;
        assertNotNull(first);
        result = null;
        tapCenter();
        view.pause();
        idle(5000);
        assertNull(result);
    }

    // ------------------------------------------------------------------ helpers

    /** What the drill's screen currently says (its accessibility description). */
    private String said() {
        CharSequence d = view.getContentDescription();
        return d == null ? "" : d.toString();
    }

    private interface Check {
        boolean ok();
    }

    private Check green() {
        return new Check() {
            @Override
            public boolean ok() {
                return pixel(5, view.getHeight() / 2) == Ui.GO;
            }
        };
    }

    private static void idle(long ms) {
        shadowOf(getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private void waitFor(Check check, long maxMs) {
        long waited = 0;
        while (!check.ok()) {
            idle(STEP_MS);
            waited += STEP_MS;
            if (waited > maxMs) fail("condition not met within " + maxMs + " ms");
        }
    }

    private Bitmap frame;

    /** Draws the view into one reused bitmap (bitmaps are native memory the GC can't see). */
    private Bitmap render() {
        if (frame == null || frame.getWidth() != view.getWidth() || frame.getHeight() != view.getHeight()) {
            frame = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        }
        frame.eraseColor(0);
        view.draw(new Canvas(frame));
        return frame;
    }

    private int pixel(float x, float y) {
        return render().getPixel((int) x, (int) y);
    }

    /** Centroid of every pixel of exactly {@code color}, or null if there are none. */
    private float[] findColor(int color) {
        Bitmap b = render();
        long sx = 0, sy = 0, n = 0;
        for (int y = 0; y < b.getHeight(); y += 2) {
            for (int x = 0; x < b.getWidth(); x += 2) {
                if (b.getPixel(x, y) == color) {
                    sx += x;
                    sy += y;
                    n++;
                }
            }
        }
        return n == 0 ? null : new float[] {(float) sx / n, (float) sy / n};
    }

    /** Bounding box {left, top, right, bottom} of pixels of exactly {@code color}, or null. */
    private float[] findBounds(int color) {
        Bitmap b = render();
        int l = Integer.MAX_VALUE, t = Integer.MAX_VALUE, r = -1, btm = -1;
        for (int y = 0; y < b.getHeight(); y++) {
            for (int x = 0; x < b.getWidth(); x++) {
                if (b.getPixel(x, y) == color) {
                    l = Math.min(l, x);
                    t = Math.min(t, y);
                    r = Math.max(r, x);
                    btm = Math.max(btm, y);
                }
            }
        }
        return r < 0 ? null : new float[] {l, t, r + 1, btm + 1};
    }

    private float[] waitForBounds(int color, long maxMs) {
        long waited = 0;
        float[] box;
        while ((box = findBounds(color)) == null) {
            idle(STEP_MS);
            waited += STEP_MS;
            if (waited > maxMs) fail("never saw the stimulus");
        }
        return box;
    }

    private float[] waitForColor(int color, long maxMs) {
        long waited = 0;
        float[] at;
        while ((at = findColor(color)) == null) {
            if (result != null) return null;
            idle(STEP_MS);
            waited += STEP_MS;
            if (waited > maxMs) fail("never saw the stimulus");
        }
        return at;
    }

    private void tapCenter() {
        tap(view.getWidth() / 2f, view.getHeight() / 2f);
    }

    private void tap(float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0);
        view.dispatchTouchEvent(down);
        view.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }
}

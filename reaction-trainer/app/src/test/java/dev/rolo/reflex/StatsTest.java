package dev.rolo.reflex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

public class StatsTest {
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static Session simple(long t, int... times) {
        return new Session(Mode.SIMPLE, t, times, times.length, 0, 0, 0);
    }

    @Test
    public void basicStatistics() {
        int[] v = {200, 300, 250, 250};
        assertEquals(250, Stats.mean(v), 1e-9);
        assertEquals(250, Stats.median(v), 1e-9);
        assertEquals(200, Stats.min(v));
        assertEquals(300, Stats.max(v));
        assertEquals(Math.sqrt(5000.0 / 3), Stats.sd(v), 1e-9);
        assertEquals(240, Stats.median(new int[] {300, 240, 200}), 1e-9);
    }

    @Test
    public void emptyInputs() {
        int[] none = {};
        assertTrue(Double.isNaN(Stats.mean(none)));
        assertTrue(Double.isNaN(Stats.median(none)));
        assertEquals(0, Stats.sd(none), 0);
        assertEquals(0, Stats.sd(new int[] {250}), 0);
        assertEquals(-1, Stats.min(none));
        assertEquals(-1, Stats.max(none));
        assertNull(Stats.personalBest(new ArrayList<Session>()));
        assertTrue(Double.isNaN(Stats.recentAverage(new ArrayList<Session>(), 10)));
        assertEquals(-1, Stats.fastestSingle(new ArrayList<Session>()));
    }

    @Test
    public void personalBestIsTheLowestQualifyingAverage() {
        Session slow = simple(1, 300, 310, 320);
        Session fast = simple(2, 240, 250, 260);
        Session tooShort = simple(3, 150, 150); // fewer than PB_MIN_TIMES reactions
        assertSame(fast, Stats.personalBest(Arrays.asList(slow, fast, tooShort)));
    }

    @Test
    public void inaccurateRoundsCannotSetABest() {
        // Fast, but half the taps were wrong: tapping everything shouldn't win Go / No-Go.
        Session sloppy = new Session(Mode.GO_NO_GO, 1, new int[] {200, 210, 220}, 3, 3, 0, 0);
        Session careful = new Session(Mode.GO_NO_GO, 2, new int[] {300, 310, 320}, 10, 0, 0, 0);
        assertFalse(sloppy.qualifies());
        assertTrue(careful.qualifies());
        assertSame(careful, Stats.personalBest(Arrays.asList(sloppy, careful)));
        assertEquals(0.5, sloppy.accuracy(), 1e-9);
    }

    @Test
    public void simpleAndSoundModesIgnoreAccuracyForBests() {
        Session s = new Session(Mode.SIMPLE, 1, new int[] {250, 260, 270}, 3, 0, 0, 5);
        assertTrue(s.qualifies());
        Session sound = new Session(Mode.SOUND, 1, new int[] {200, 210, 220}, 3, 0, 0, 5);
        assertTrue(sound.qualifies());
        assertFalse(Mode.SOUND.scoresAccuracy());
        assertEquals(10, Mode.SOUND.trials(Prefs.LENGTH_STANDARD));
    }

    @Test
    public void recentAverageUsesTheLatestRounds() {
        List<Session> h = Arrays.asList(simple(1, 500), simple(2, 300), simple(3, 200));
        assertEquals(250, Stats.recentAverage(h, 2), 1e-9);
        assertEquals(1000 / 3.0, Stats.recentAverage(h, 10), 1e-9);
    }

    @Test
    public void recentAccuracyPoolsAttempts() {
        List<Session> h = Arrays.asList(
                new Session(Mode.CHOICE, 1, new int[] {300}, 1, 1, 0, 0),
                new Session(Mode.CHOICE, 2, new int[] {300, 300, 300}, 3, 0, 0, 0));
        assertEquals(4 / 5.0, Stats.recentAccuracy(h, 10), 1e-9);
        assertEquals(1.0, Stats.recentAccuracy(h, 1), 1e-9);
    }

    @Test
    public void fastestSingleAcrossRounds() {
        assertEquals(190, Stats.fastestSingle(Arrays.asList(simple(1, 250, 190), simple(2, 230))));
    }

    @Test
    public void dayStreakCountsBackFromTodayOrYesterday() {
        long today = 100 * DAY + 12 * 60 * 60 * 1000L; // noon, day 100
        List<Session> h = Arrays.asList(
                simple(today - 3 * DAY, 250), // gap on day 96 before this run
                simple(today - 2 * DAY, 250),
                simple(today - DAY, 250),
                simple(today - DAY + 1000, 250));
        assertEquals(3, Stats.dayStreak(h, today, UTC)); // not played yet today: still alive
        List<Session> withToday = new ArrayList<>(h);
        withToday.add(simple(today, 250));
        assertEquals(4, Stats.dayStreak(withToday, today, UTC));
        assertEquals(0, Stats.dayStreak(h, today + 2 * DAY, UTC)); // skipped a whole day
        assertEquals(0, Stats.dayStreak(new ArrayList<Session>(), today, UTC));
    }

    @Test
    public void dayStreakUsesLocalDays() {
        // 23:30 and 00:30 local time in UTC+2 are different days even though they're an hour apart.
        TimeZone plus2 = TimeZone.getTimeZone("GMT+02:00");
        long lateEvening = 50 * DAY + (21 * 60 + 30) * 60 * 1000L; // 23:30 local
        long pastMidnight = lateEvening + 60 * 60 * 1000L;          // 00:30 local, next day
        assertEquals(2, Stats.dayStreak(Arrays.asList(simple(lateEvening, 250), simple(pastMidnight, 250)),
                pastMidnight, plus2));
    }

    @Test
    public void niceSteps() {
        assertEquals(10, Stats.niceStep(9), 1e-9);
        assertEquals(20, Stats.niceStep(19), 1e-9);
        assertEquals(25, Stats.niceStep(30), 1e-9);
        assertEquals(50, Stats.niceStep(48), 1e-9);
        assertEquals(100, Stats.niceStep(80), 1e-9);
        assertEquals(1, Stats.niceStep(0), 1e-9);
    }

    @Test
    public void roundLengthsClamp() {
        assertEquals(5, Mode.SIMPLE.trials(-1));
        assertEquals(10, Mode.SIMPLE.trials(Prefs.LENGTH_STANDARD));
        assertEquals(20, Mode.SIMPLE.trials(99));
    }
}

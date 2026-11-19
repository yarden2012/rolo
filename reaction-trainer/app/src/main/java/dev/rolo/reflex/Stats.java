package dev.rolo.reflex;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/** Plain-Java statistics over reaction times and round history. No Android dependencies. */
public final class Stats {
    /** A round must be at least this accurate (in modes with wrong answers) to set a best. */
    public static final double PB_MIN_ACCURACY = 0.8;
    /** A round needs at least this many valid reactions to set a best. */
    public static final int PB_MIN_TIMES = 3;

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private Stats() {}

    public static double mean(int[] v) {
        if (v.length == 0) return Double.NaN;
        long sum = 0;
        for (int x : v) sum += x;
        return (double) sum / v.length;
    }

    public static double median(int[] v) {
        if (v.length == 0) return Double.NaN;
        int[] s = v.clone();
        Arrays.sort(s);
        int mid = s.length / 2;
        return s.length % 2 == 1 ? s[mid] : (s[mid - 1] + s[mid]) / 2.0;
    }

    /** Sample standard deviation; 0 for fewer than two values. */
    public static double sd(int[] v) {
        if (v.length < 2) return 0;
        double m = mean(v);
        double acc = 0;
        for (int x : v) acc += (x - m) * (x - m);
        return Math.sqrt(acc / (v.length - 1));
    }

    /** Smallest value, or -1 when empty. */
    public static int min(int[] v) {
        if (v.length == 0) return -1;
        int best = Integer.MAX_VALUE;
        for (int x : v) best = Math.min(best, x);
        return best;
    }

    /** Largest value, or -1 when empty. */
    public static int max(int[] v) {
        if (v.length == 0) return -1;
        int worst = Integer.MIN_VALUE;
        for (int x : v) worst = Math.max(worst, x);
        return worst;
    }

    /** The qualifying round with the lowest average, or null if none qualifies. */
    public static Session personalBest(List<Session> history) {
        Session best = null;
        for (Session s : history) {
            if (!s.qualifies()) continue;
            if (best == null || s.mean() < best.mean()) best = s;
        }
        return best;
    }

    /** Average of the round averages of the last {@code n} rounds that have times; NaN if none. */
    public static double recentAverage(List<Session> history, int n) {
        double sum = 0;
        int used = 0;
        for (int i = history.size() - 1; i >= 0 && used < n; i--) {
            Session s = history.get(i);
            if (!s.hasTimes()) continue;
            sum += s.mean();
            used++;
        }
        return used == 0 ? Double.NaN : sum / used;
    }

    /** Pooled accuracy over the last {@code n} rounds; NaN if none. */
    public static double recentAccuracy(List<Session> history, int n) {
        long correct = 0;
        long attempts = 0;
        for (int i = history.size() - 1, used = 0; i >= 0 && used < n; i--, used++) {
            Session s = history.get(i);
            correct += s.correct;
            attempts += s.attempts();
        }
        return attempts == 0 ? Double.NaN : (double) correct / attempts;
    }

    /** Fastest single reaction across all rounds, or -1 if there are none. */
    public static int fastestSingle(List<Session> history) {
        int best = -1;
        for (Session s : history) {
            int b = s.best();
            if (b >= 0 && (best < 0 || b < best)) best = b;
        }
        return best;
    }

    /**
     * Consecutive calendar days with at least one round, counting back from today. A streak
     * isn't broken until a whole day is skipped, so if you haven't played yet today it counts
     * back from yesterday.
     */
    public static int dayStreak(List<Session> history, long nowMs, TimeZone zone) {
        Set<Long> days = new HashSet<>();
        for (Session s : history) days.add(localDay(s.timestamp, zone));
        long day = localDay(nowMs, zone);
        if (!days.contains(day)) day--;
        int streak = 0;
        while (days.contains(day)) {
            streak++;
            day--;
        }
        return streak;
    }

    static long localDay(long epochMs, TimeZone zone) {
        return Math.floorDiv(epochMs + zone.getOffset(epochMs), DAY_MS);
    }

    /** A "nice" axis step (1, 2, 2.5 or 5 times a power of ten) close to {@code rough}. */
    public static double niceStep(double rough) {
        if (!(rough > 0)) return 1;
        double pow = Math.pow(10, Math.floor(Math.log10(rough)));
        double f = rough / pow;
        double nice = f < 1.5 ? 1 : f < 2.25 ? 2 : f < 3.5 ? 2.5 : f < 7.5 ? 5 : 10;
        return nice * pow;
    }
}

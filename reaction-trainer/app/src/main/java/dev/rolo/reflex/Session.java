package dev.rolo.reflex;

import java.util.Arrays;

/** One finished round. Immutable. */
public final class Session {
    public final Mode mode;
    /** Wall-clock time the round ended, in epoch milliseconds. */
    public final long timestamp;
    /** Valid reaction times in milliseconds, in the order they happened. */
    private final int[] times;
    /** Correct responses: valid reactions, plus correctly withheld taps in Go / No-Go. */
    public final int correct;
    /** Wrong responses: wrong pad, tapping a red square, or a tap that missed the target. */
    public final int errors;
    /** Stimuli that got no response in time. */
    public final int misses;
    /** Taps before the stimulus (or too fast after it to be a real reaction). */
    public final int falseStarts;

    public Session(Mode mode, long timestamp, int[] times, int correct, int errors, int misses, int falseStarts) {
        this.mode = mode;
        this.timestamp = timestamp;
        this.times = times.clone();
        this.correct = correct;
        this.errors = errors;
        this.misses = misses;
        this.falseStarts = falseStarts;
    }

    public int count() {
        return times.length;
    }

    public int time(int index) {
        return times[index];
    }

    public int[] times() {
        return times.clone();
    }

    public boolean hasTimes() {
        return times.length > 0;
    }

    public double mean() {
        return Stats.mean(times);
    }

    public double median() {
        return Stats.median(times);
    }

    public double sd() {
        return Stats.sd(times);
    }

    public int best() {
        return Stats.min(times);
    }

    public int attempts() {
        return correct + errors + misses + falseStarts;
    }

    /** Share of attempts that were correct, 0..1. */
    public double accuracy() {
        int attempts = attempts();
        return attempts == 0 ? 0 : (double) correct / attempts;
    }

    /**
     * Whether this round can count as a personal best. A round needs a few real reactions,
     * and in modes with right and wrong answers it must be mostly right: otherwise tapping
     * everything as fast as possible would "win".
     */
    public boolean qualifies() {
        if (times.length < Stats.PB_MIN_TIMES) return false;
        return !mode.scoresAccuracy() || accuracy() >= Stats.PB_MIN_ACCURACY;
    }

    @Override
    public String toString() {
        return "Session{" + mode.id + " " + Arrays.toString(times) + " c=" + correct + " e=" + errors
                + " m=" + misses + " f=" + falseStarts + "}";
    }
}

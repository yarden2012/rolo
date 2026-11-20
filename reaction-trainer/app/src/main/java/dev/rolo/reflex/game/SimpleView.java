package dev.rolo.reflex.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.text.StaticLayout;
import android.view.KeyEvent;

import dev.rolo.reflex.Mode;
import dev.rolo.reflex.R;
import dev.rolo.reflex.Ui;

/**
 * Simple reaction to a single cue. Tap reaction: the whole screen turns from dark red to bright
 * green. Sound reaction: a beep, while the screen stays exactly as it was.
 */
final class SimpleView extends TrainerView {
    private static final int TIMEOUT_MS = 2500;
    /** Result screens ignore taps this soon after appearing, so a double tap can't skip them. */
    private static final long RESULT_GUARD_NANOS = 250_000_000L;
    /** People react to sound roughly this much faster than to sight, so ratings shift by it. */
    private static final int AUDIO_ADVANTAGE_MS = 40;

    private enum State { INTRO, WAITING, GO, RESULT, TOO_SOON, MISSED, PAUSED }

    /** The beep for Sound reaction; null for Tap reaction. */
    private final AudioCue cue;
    private boolean audioFailed;
    private StaticLayout introWarning;

    private State state = State.INTRO;
    private long onsetNanos;
    private long shownAtNanos;
    private int lastMs;

    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            misses++;
            buzz();
            show(State.MISSED);
        }
    };

    private final Runnable beep = new Runnable() {
        @Override
        public void run() {
            cue.play();
            state = State.GO; // deliberately no redraw: the screen must not change
            handler.postDelayed(timeout, TIMEOUT_MS);
        }
    };

    SimpleView(Context context, Mode mode, int trials, AudioCue cue) {
        super(context, mode, trials);
        this.cue = cue;
    }

    private int trialsDone() {
        return recordedCount() + misses;
    }

    @Override
    protected void onTap(float x, float y, long t) {
        switch (state) {
            case WAITING:
                cancelPending();
                falseStarts++;
                buzz();
                show(State.TOO_SOON);
                break;
            case GO: {
                handler.removeCallbacks(timeout);
                long onset = cue != null ? cue.onsetNanos() : onsetNanos;
                // A tap before the beep was even sent to the speaker is an anticipation too.
                int ms = onset < 0 ? -1 : elapsedMs(onset, t);
                if (ms < MIN_REACTION_MS) {
                    falseStarts++;
                    buzz();
                    show(State.TOO_SOON);
                } else {
                    recordTime(ms);
                    correct++;
                    lastMs = ms;
                    tick();
                    show(State.RESULT);
                }
                break;
            }
            case RESULT:
            case TOO_SOON:
            case MISSED:
                if (t - shownAtNanos < RESULT_GUARD_NANOS) return;
                advance();
                break;
            case INTRO:
            case PAUSED:
                advance();
                break;
        }
    }

    private void advance() {
        if (trialsDone() >= totalTrials) {
            if (cue != null) cue.release();
            finishRound();
            return;
        }
        if (cue != null) {
            audioFailed = !cue.start();
            if (audioFailed) {
                show(State.INTRO); // explains that sound isn't available
                return;
            }
            show(State.WAITING);
            handler.postDelayed(beep, foreperiodMs());
            return;
        }
        show(State.WAITING);
        scheduleStimulus(foreperiodMs(), new Stimulus() {
            @Override
            public void show(long onset) {
                onsetNanos = onset;
                state = State.GO;
                handler.postDelayed(timeout, TIMEOUT_MS);
            }
        });
    }

    /**
     * 1.2 s plus an exponential wait (mean 1.3 s), capped at 5 s. An exponential wait has
     * no "it must be any moment now" build-up, so you can't beat it by counting.
     */
    private long foreperiodMs() {
        double exp = -Math.log(1 - random.nextDouble()) * 1300;
        return Math.min(5000, 1200 + (long) exp);
    }

    private void show(State s) {
        state = s;
        shownAtNanos = nowNanos();
        invalidate();
    }

    @Override
    protected void onPause() {
        if (cue != null) cue.release(); // reopened when the round resumes
        if (state == State.WAITING || state == State.GO) state = State.PAUSED;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Open the stream while the start card is up, so it's warmed up by the first beep.
        if (cue != null) audioFailed = !cue.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (cue != null) cue.release();
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // The volume keys change media volume here; refresh the "volume is off" warning.
        if (cue != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE)) {
            postInvalidateDelayed(150);
        }
        return super.onKeyUp(keyCode, event);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        switch (state) {
            case INTRO:
                c.drawColor(Ui.BG);
                drawIntro(c);
                return;
            case WAITING:
                if (cue != null) {
                    drawListening(c, cx, cy);
                    break;
                }
                c.drawColor(Ui.WAIT);
                setText(30, Ui.TEXT, Ui.BOLD);
                drawCentered(c, str(R.string.simple_wait), cx, cy);
                break;
            case GO:
                if (cue != null) {
                    drawListening(c, cx, cy); // identical to waiting: the beep is the only cue
                    break;
                }
                c.drawColor(Ui.GO);
                setText(80, Ui.ON_GO, Ui.BLACK);
                drawCentered(c, str(R.string.simple_tap), cx, cy);
                return; // nothing else on screen: the change itself is the cue
            case RESULT: {
                c.drawColor(Ui.BG);
                setText(96, Ui.TEXT, Ui.BLACK);
                drawCentered(c, String.valueOf(lastMs), cx, cy - dp(40));
                setText(20, Ui.TEXT2, Ui.MEDIUM);
                drawCentered(c, str(R.string.unit_ms), cx, cy + dp(28));
                boolean best = recordedCount() > 1 && lastMs == recordedBest();
                setText(18, best ? Ui.GO : Ui.TEXT, Ui.MEDIUM);
                int rating = rating(cue != null ? lastMs + AUDIO_ADVANTAGE_MS : lastMs);
                drawCentered(c, best ? str(R.string.best_of_round) : str(rating), cx, cy + dp(70));
                drawContinueHint(c);
                break;
            }
            case TOO_SOON:
                c.drawColor(Ui.WARN_BG);
                setText(40, Ui.TEXT, Ui.BLACK);
                drawCentered(c, str(R.string.too_soon), cx, cy - dp(20));
                setText(17, Ui.TEXT2, Ui.REGULAR);
                drawCentered(c, str(cue != null ? R.string.too_soon_sound : R.string.too_soon_body), cx, cy + dp(24));
                setText(15, Ui.TEXT2, Ui.REGULAR); // the muted grey is too dim on amber
                drawCentered(c, str(R.string.try_again), cx, getHeight() - safeBottom - dp(56));
                break;
            case MISSED:
                c.drawColor(Ui.BG);
                setText(40, Ui.TEXT, Ui.BLACK);
                drawCentered(c, str(R.string.missed), cx, cy - dp(20));
                setText(17, Ui.TEXT2, Ui.REGULAR);
                drawCentered(c, str(R.string.missed_body), cx, cy + dp(24));
                drawContinueHint(c);
                break;
            case PAUSED:
                c.drawColor(Ui.BG);
                drawPaused(c);
                break;
        }
        drawHud(c, trialsDone(), Ui.TEXT2);
    }

    /** Sound reaction's screen while waiting, before and after the beep alike. */
    private void drawListening(Canvas c, float cx, float cy) {
        c.drawColor(Ui.BG);
        Drawable icon = getContext().getDrawable(mode.icon);
        if (icon != null) {
            int half = Math.round(dp(28));
            icon = icon.mutate();
            icon.setTint(Ui.TEXT3);
            icon.setBounds(Math.round(cx) - half, Math.round(cy - dp(84)) - half,
                    Math.round(cx) + half, Math.round(cy - dp(84)) + half);
            icon.draw(c);
        }
        setText(30, Ui.TEXT, Ui.BOLD);
        drawCentered(c, str(R.string.sound_listen), cx, cy);
        setText(16, Ui.TEXT2, Ui.REGULAR);
        drawCentered(c, str(R.string.sound_listen_body), cx, cy + dp(36));
        if (ToneCue.volumeOff(getContext())) {
            setText(15, Ui.WARN, Ui.MEDIUM);
            drawCentered(c, str(R.string.sound_volume_off_short), cx, cy + dp(84));
        }
    }

    @Override
    protected float introExtraHeight() {
        String warning = cue != null ? audioWarning() : null;
        if (warning == null) {
            introWarning = null;
            return 0;
        }
        setText(14, Ui.WARN, Ui.REGULAR);
        introWarning = wrap(warning, Math.min(getWidth() - safeLeft - safeRight - dp(48), dp(360)));
        return introWarning.getHeight();
    }

    @Override
    protected void drawIntroExtra(Canvas c, float cx, float cy) {
        if (introWarning != null) drawLayout(c, introWarning, cx, cy - introWarning.getHeight() / 2f);
    }

    /** Anything that would make Sound reaction silent or inaccurate, worst first. */
    private String audioWarning() {
        if (audioFailed) return str(R.string.sound_unavailable);
        if (ToneCue.volumeOff(getContext())) return str(R.string.sound_volume_off);
        if (ToneCue.bluetoothOutput(getContext())) return str(R.string.sound_bluetooth);
        return null;
    }

    private void drawContinueHint(Canvas c) {
        setText(15, Ui.TEXT3, Ui.REGULAR);
        int hint = trialsDone() >= totalTrials ? R.string.tap_for_results : R.string.tap_to_continue;
        drawCentered(c, str(hint), getWidth() / 2f, getHeight() - safeBottom - dp(56));
    }

    /** Rough bands for touchscreen times, which run a little slower than lab button presses. */
    private static int rating(int ms) {
        if (ms < 200) return R.string.rating_1;
        if (ms < 240) return R.string.rating_2;
        if (ms < 280) return R.string.rating_3;
        if (ms < 330) return R.string.rating_4;
        if (ms < 400) return R.string.rating_5;
        return R.string.rating_6;
    }
}

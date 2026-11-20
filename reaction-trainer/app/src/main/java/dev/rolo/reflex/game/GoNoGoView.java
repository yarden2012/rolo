package dev.rolo.reflex.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;

import dev.rolo.reflex.Mode;
import dev.rolo.reflex.R;
import dev.rolo.reflex.Ui;

/**
 * Go / No-Go: a green circle means tap, a red square means hold. Go trials are the common case
 * (75%), which builds the urge to tap that the no-go trials then test you on.
 */
final class GoNoGoView extends TrainerView {
    private static final int WINDOW_MS = 1000;
    private static final int FEEDBACK_MS = 450;
    private static final int EARLY_FEEDBACK_MS = 800;
    private static final float NO_GO_SHARE = 0.25f;

    private enum State { INTRO, FIXATION, STIMULUS, FEEDBACK, PAUSED }

    private enum Outcome { HIT, HOLD, COMMISSION, OMISSION, EARLY }

    private final boolean[] sequence;
    private int index;
    private State state = State.INTRO;
    private Outcome outcome;
    private long onsetNanos;
    private int lastMs;

    private final Runnable windowEnd = new Runnable() {
        @Override
        public void run() {
            boolean go = sequence[index];
            index++;
            if (go) {
                misses++;
                buzz();
                feedback(Outcome.OMISSION);
            } else {
                correct++; // held back on red: correct
                feedback(Outcome.HOLD);
            }
        }
    };

    private final Runnable next = new Runnable() {
        @Override
        public void run() {
            advance();
        }
    };

    GoNoGoView(Context context, Mode mode, int trials) {
        super(context, mode, trials);
        sequence = new boolean[trials];
        int noGo = Math.round(trials * NO_GO_SHARE);
        for (int i = 0; i < trials; i++) sequence[i] = i >= noGo;
        for (int i = trials - 1; i > 0; i--) { // Fisher-Yates
            int j = random.nextInt(i + 1);
            boolean tmp = sequence[i];
            sequence[i] = sequence[j];
            sequence[j] = tmp;
        }
        // Open with a go trial, so the first thing you learn is the tap.
        if (trials > 0 && !sequence[0]) {
            for (int i = 1; i < trials; i++) {
                if (sequence[i]) {
                    sequence[i] = false;
                    sequence[0] = true;
                    break;
                }
            }
        }
    }

    @Override
    protected void onTap(float x, float y, long t) {
        switch (state) {
            case INTRO:
            case PAUSED:
                advance();
                break;
            case FIXATION:
                cancelPending();
                falseStarts++;
                buzz();
                feedback(Outcome.EARLY);
                break;
            case STIMULUS: {
                handler.removeCallbacks(windowEnd);
                int ms = elapsedMs(onsetNanos, t);
                if (ms < 0) {
                    // Before the shape could be on screen: too early, and the trial runs again.
                    falseStarts++;
                    buzz();
                    feedback(Outcome.EARLY);
                    return;
                }
                boolean go = sequence[index];
                index++;
                if (go) {
                    recordTime(ms);
                    correct++;
                    lastMs = ms;
                    tick();
                    feedback(Outcome.HIT);
                } else {
                    errors++;
                    buzz();
                    feedback(Outcome.COMMISSION);
                }
                break;
            }
            case FEEDBACK:
                break;
        }
    }

    private void feedback(Outcome o) {
        state = State.FEEDBACK;
        outcome = o;
        handler.postDelayed(next, o == Outcome.EARLY ? EARLY_FEEDBACK_MS : FEEDBACK_MS);
        invalidate();
    }

    private void advance() {
        if (index >= totalTrials) {
            finishRound();
            return;
        }
        state = State.FIXATION;
        invalidate();
        scheduleStimulus(uniformMs(700, 1500), new Stimulus() {
            @Override
            public void show(long onset) {
                onsetNanos = onset;
                state = State.STIMULUS;
                handler.postDelayed(windowEnd, WINDOW_MS);
            }
        });
    }

    @Override
    protected void onPause() {
        if (state != State.INTRO) state = State.PAUSED;
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = (safeLeft + getWidth() - safeRight) / 2f;
        float cy = getHeight() / 2f;

        if (state == State.FEEDBACK && outcome == Outcome.COMMISSION) {
            c.drawColor(Ui.BG);
            c.drawColor(Ui.withAlpha(Ui.STOP, 0.14f));
        } else {
            c.drawColor(Ui.BG);
        }
        if (state == State.INTRO) {
            drawIntro(c);
            return;
        }
        drawHud(c, index, Ui.TEXT2);
        if (state == State.PAUSED) {
            drawPaused(c);
            return;
        }

        if (state == State.STIMULUS) {
            if (sequence[index]) drawGo(c, cx, cy, dp(76));
            else drawNoGo(c, cx, cy, dp(72));
        } else {
            drawFixation(c, cx, cy);
        }

        if (state == State.FEEDBACK) {
            String message;
            int color = Ui.TEXT;
            switch (outcome) {
                case HIT:
                    message = str(R.string.fb_ms, lastMs);
                    break;
                case HOLD:
                    message = str(R.string.fb_hold);
                    color = Ui.TEXT2;
                    break;
                case COMMISSION:
                    message = str(R.string.fb_commission);
                    break;
                case OMISSION:
                    message = str(R.string.fb_omission);
                    break;
                case EARLY:
                default:
                    message = str(R.string.fb_too_early_shape);
                    break;
            }
            setText(20, color, Ui.MEDIUM);
            drawCentered(c, message, cx, cy + dp(120));
        }
        drawLegend(c, getHeight() - safeBottom - dp(40));
    }

    @Override
    protected float introExtraHeight() {
        return dp(28);
    }

    @Override
    protected void drawIntroExtra(Canvas c, float cx, float cy) {
        drawLegend(c, cy);
    }

    private void drawFixation(Canvas c, float cx, float cy) {
        float arm = dp(14);
        stroke.setColor(Ui.TEXT3);
        stroke.setStrokeWidth(dp(3));
        c.drawLine(cx - arm, cy, cx + arm, cy, stroke);
        c.drawLine(cx, cy - arm, cx, cy + arm, stroke);
    }

    private void drawGo(Canvas c, float cx, float cy, float r) {
        fill.setColor(Ui.GO);
        c.drawCircle(cx, cy, r, fill);
    }

    private void drawNoGo(Canvas c, float cx, float cy, float half) {
        RectF r = tmpRect();
        r.set(cx - half, cy - half, cx + half, cy + half);
        fill.setColor(Ui.STOP);
        c.drawRoundRect(r, half * 0.22f, half * 0.22f, fill);
        float arm = half * 0.42f;
        stroke.setColor(Ui.TEXT);
        stroke.setStrokeWidth(half * 0.16f);
        c.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, stroke);
        c.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, stroke);
    }

    /** "● Tap    ■ Hold": shape and word, so the rule never rests on colour alone. */
    private void drawLegend(Canvas c, float y) {
        setText(15, Ui.TEXT2, Ui.REGULAR);
        String tap = str(R.string.legend_tap);
        String hold = str(R.string.legend_hold);
        float icon = dp(12);
        float gapIcon = dp(8);
        float gapItems = dp(28);
        float wTap = icon + gapIcon + text.measureText(tap);
        float wHold = icon + gapIcon + text.measureText(hold);
        float x = getWidth() / 2f - (wTap + gapItems + wHold) / 2f;

        drawGo(c, x + icon / 2f, y, icon / 2f);
        text.setTextAlign(android.graphics.Paint.Align.LEFT);
        android.graphics.Paint.FontMetrics fm = text.getFontMetrics();
        float baseline = y - (fm.ascent + fm.descent) / 2f;
        c.drawText(tap, x + icon + gapIcon, baseline, text);

        x += wTap + gapItems;
        RectF r = tmpRect();
        r.set(x, y - icon / 2f, x + icon, y + icon / 2f);
        fill.setColor(Ui.STOP);
        c.drawRoundRect(r, dp(2), dp(2), fill);
        c.drawText(hold, x + icon + gapIcon, baseline, text);
    }
}

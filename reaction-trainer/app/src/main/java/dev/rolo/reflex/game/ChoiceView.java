package dev.rolo.reflex.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;

import dev.rolo.reflex.Mode;
import dev.rolo.reflex.R;
import dev.rolo.reflex.Ui;

/** Four-choice reaction: one of four pads lights up; tap that one. */
final class ChoiceView extends TrainerView {
    private static final int TIMEOUT_MS = 2000;
    private static final int FEEDBACK_MS = 420;
    private static final int EARLY_FEEDBACK_MS = 800;

    private enum State { INTRO, WAITING, LIT, FEEDBACK, PAUSED }

    /** EARLY: tapped before any pad lit. FAST: after, but under 100 ms, so it began before. */
    private enum Outcome { HIT, WRONG, MISS, EARLY, FAST }

    private State state = State.INTRO;
    private Outcome outcome;
    private final RectF[] pads = {new RectF(), new RectF(), new RectF(), new RectF()};
    private int lit = -1;
    private int tapped = -1;
    private int lastLit = -1;
    private int repeats;
    private int lastMs;
    private long onsetNanos;
    private int trialsDone;

    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            trialsDone++;
            misses++;
            buzz();
            feedback(Outcome.MISS, -1);
        }
    };

    private final Runnable next = new Runnable() {
        @Override
        public void run() {
            advance();
        }
    };

    ChoiceView(Context context, Mode mode, int trials) {
        super(context, mode, trials);
    }

    @Override
    protected boolean acceptsKeys() {
        return false; // which pad would a key press mean?
    }

    @Override
    protected void onGeometryChanged() {
        float margin = dp(20);
        float left = safeLeft + margin;
        float right = getWidth() - safeRight - margin;
        float top = safeTop + dp(76);
        float bottom = getHeight() - safeBottom - dp(84);
        float side = Math.max(0, Math.min(right - left, bottom - top));
        float gap = dp(14);
        float cell = (side - gap) / 2f;
        float ox = (left + right) / 2f - side / 2f;
        float oy = (top + bottom) / 2f - side / 2f;
        for (int i = 0; i < 4; i++) {
            float x = ox + (i % 2) * (cell + gap);
            float y = oy + (i / 2) * (cell + gap);
            pads[i].set(x, y, x + cell, y + cell);
        }
    }

    /**
     * Which pad a tap chose. Each pad owns its whole quadrant of the grid, gaps included, plus a
     * margin around the grid: the drill tests which pad you pick, not how precisely you hit it,
     * and at speed a finger often lands just beside the pad it was going for. Touches well away
     * from the grid (a palm, a brush on the top bar) return -1 and are ignored, not scored.
     */
    private int padAt(float x, float y) {
        float slop = dp(40);
        if (x < pads[0].left - slop || x > pads[1].right + slop
                || y < pads[0].top - slop || y > pads[2].bottom + slop) {
            return -1;
        }
        int col = x < (pads[0].right + pads[1].left) / 2f ? 0 : 1;
        int row = y < (pads[0].bottom + pads[2].top) / 2f ? 0 : 1;
        return row * 2 + col;
    }

    @Override
    protected void onTap(float x, float y, long t) {
        switch (state) {
            case INTRO:
            case PAUSED:
                advance();
                break;
            case WAITING:
                if (padAt(x, y) < 0) return; // a stray touch, not a jump at a pad
                cancelPending();
                falseStarts++;
                buzz();
                feedback(Outcome.EARLY, -1);
                break;
            case LIT: {
                int hit = padAt(x, y);
                if (hit < 0) return; // a stray touch, not an answer
                handler.removeCallbacks(timeout);
                int ms = elapsedMs(onsetNanos, t);
                if (ms < MIN_REACTION_MS) {
                    falseStarts++;
                    buzz();
                    lastMs = ms;
                    // An anticipation doesn't use up the trial.
                    feedback(ms >= 0 ? Outcome.FAST : Outcome.EARLY, -1);
                    return;
                }
                trialsDone++;
                if (hit == lit) {
                    recordTime(ms);
                    correct++;
                    lastMs = ms;
                    tick();
                    feedback(Outcome.HIT, hit);
                } else {
                    errors++;
                    buzz();
                    feedback(Outcome.WRONG, hit);
                }
                break;
            }
            case FEEDBACK:
                break; // brief, and taps here aren't meaningful
        }
    }

    private void feedback(Outcome o, int pad) {
        state = State.FEEDBACK;
        outcome = o;
        tapped = pad;
        boolean jump = o == Outcome.EARLY || o == Outcome.FAST;
        if (jump) lit = -1;
        handler.postDelayed(next, jump ? EARLY_FEEDBACK_MS : FEEDBACK_MS);
        invalidate();
    }

    private void advance() {
        if (trialsDone >= totalTrials) {
            finishRound();
            return;
        }
        state = State.WAITING;
        lit = -1;
        tapped = -1;
        invalidate();
        scheduleStimulus(uniformMs(700, 1800), new Stimulus() {
            @Override
            public void show(long onset) {
                lit = pickPad();
                onsetNanos = onset;
                state = State.LIT;
                handler.postDelayed(timeout, TIMEOUT_MS);
            }
        });
    }

    /** Random pad, but never the same one three times running. */
    private int pickPad() {
        int p;
        do {
            p = random.nextInt(4);
        } while (p == lastLit && repeats >= 1);
        repeats = p == lastLit ? repeats + 1 : 0;
        lastLit = p;
        return p;
    }

    @Override
    protected void onPause() {
        if (state != State.INTRO) {
            state = State.PAUSED;
            lit = -1;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Ui.BG);
        if (state == State.INTRO) {
            drawIntro(c);
            return;
        }
        drawHud(c, trialsDone, Ui.TEXT2);
        if (state == State.PAUSED) {
            drawPaused(c);
            return;
        }

        float radius = dp(24);
        for (int i = 0; i < 4; i++) {
            RectF r = pads[i];
            int color = Ui.SURFACE2;
            if (state == State.LIT && i == lit) color = Ui.CHOICE_LIT;
            if (state == State.FEEDBACK) {
                if (outcome == Outcome.HIT && i == tapped) color = Ui.GO;
                if (outcome == Outcome.WRONG && i == tapped) color = Ui.STOP;
            }
            if (color == Ui.CHOICE_LIT) {
                // A soft halo makes the lit pad pop even in peripheral vision.
                RectF halo = tmpRect();
                float g = dp(8);
                halo.set(r.left - g, r.top - g, r.right + g, r.bottom + g);
                fill.setColor(Ui.withAlpha(Ui.CHOICE_LIT, 0.22f));
                c.drawRoundRect(halo, radius + g, radius + g, fill);
            }
            fill.setColor(color);
            c.drawRoundRect(r, radius, radius, fill);

            // After a wrong tap or a miss, outline the pad that was lit.
            if (state == State.FEEDBACK && i == lit && outcome != Outcome.HIT) {
                stroke.setColor(Ui.CHOICE_LIT);
                stroke.setStrokeWidth(dp(3));
                c.drawRoundRect(r, radius, radius, stroke);
            }
            if (state == State.FEEDBACK && outcome == Outcome.HIT && i == tapped) {
                setText(30, Ui.ON_GO, Ui.BLACK);
                drawCentered(c, String.valueOf(lastMs), r.centerX(), r.centerY());
            }
        }

        String message = null;
        if (state == State.FEEDBACK) {
            switch (outcome) {
                case WRONG:
                    message = str(R.string.fb_wrong_pad);
                    break;
                case MISS:
                    message = str(R.string.fb_too_slow);
                    break;
                case EARLY:
                    message = str(R.string.fb_too_early_pad);
                    break;
                case FAST:
                    message = str(R.string.fb_too_fast, lastMs);
                    break;
                default:
                    break;
            }
        }
        if (message != null) {
            setText(16, Ui.TEXT, Ui.MEDIUM);
            drawCentered(c, message, getWidth() / 2f, getHeight() - safeBottom - dp(44));
        }
    }
}

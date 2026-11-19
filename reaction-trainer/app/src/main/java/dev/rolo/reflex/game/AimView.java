package dev.rolo.reflex.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.ArrayList;

import dev.rolo.reflex.Mode;
import dev.rolo.reflex.R;
import dev.rolo.reflex.Ui;

/**
 * Aim: targets appear one at a time somewhere new; tap each as fast as you can. Each time runs
 * from the target appearing to the touch that hits it, so it includes moving your finger there.
 */
final class AimView extends TrainerView {
    /** Taps a little outside the drawn circle still count; fingers are blunt. */
    private static final float HIT_SLOP = 1.2f;
    private static final long HIT_FX_NANOS = 320_000_000L;
    private static final long MISS_FX_NANOS = 450_000_000L;

    private enum State { INTRO, PLAYING, DONE, PAUSED }

    private static final class Effect {
        final float x, y;
        final long start;
        final boolean hit;

        Effect(float x, float y, long start, boolean hit) {
            this.x = x;
            this.y = y;
            this.start = start;
            this.hit = hit;
        }
    }

    private State state = State.INTRO;
    private float radius;
    private float tx = -1, ty = -1;
    private boolean targetVisible;
    private long onsetNanos;
    private int hits;
    private final ArrayList<Effect> effects = new ArrayList<>();
    private final float[] area = new float[4];

    private final Runnable finish = new Runnable() {
        @Override
        public void run() {
            finishRound();
        }
    };

    AimView(Context context, Mode mode, int trials) {
        super(context, mode, trials);
        radius = dp(30);
    }

    @Override
    protected boolean acceptsKeys() {
        return false;
    }

    @Override
    protected void onGeometryChanged() {
        float m = radius * HIT_SLOP + dp(8);
        area[0] = safeLeft + m;
        area[1] = safeTop + dp(64) + m;
        area[2] = getWidth() - safeRight - m;
        area[3] = getHeight() - safeBottom - dp(40) - m;
        if (tx >= 0) { // keep the current target on screen after a rotation
            tx = clamp(tx, area[0], area[2]);
            ty = clamp(ty, area[1], area[3]);
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return hi < lo ? (lo + hi) / 2f : Math.max(lo, Math.min(hi, v));
    }

    @Override
    protected void onTap(float x, float y, long t) {
        switch (state) {
            case INTRO:
            case PAUSED:
                state = State.PLAYING;
                spawn();
                break;
            case PLAYING: {
                if (!targetVisible) return; // the next target is a frame away
                float dx = x - tx;
                float dy = y - ty;
                float reach = radius * HIT_SLOP;
                if (dx * dx + dy * dy <= reach * reach) {
                    targetVisible = false;
                    recordTime(Math.max(0, elapsedMs(onsetNanos, t)));
                    correct++;
                    hits++;
                    tick();
                    effects.add(new Effect(tx, ty, nowNanos(), true));
                    if (hits >= totalTrials) {
                        state = State.DONE;
                        handler.postDelayed(finish, 350);
                    } else {
                        spawn();
                    }
                } else {
                    errors++;
                    effects.add(new Effect(x, y, nowNanos(), false));
                }
                invalidate();
                break;
            }
            case DONE:
                break;
        }
    }

    /** Picks a spot well away from the last target, then shows it on the next frame. */
    private void spawn() {
        float bestX = 0, bestY = 0, bestD = -1;
        float want = radius * 4;
        for (int i = 0; i < 30; i++) {
            float x = area[0] + random.nextFloat() * Math.max(0, area[2] - area[0]);
            float y = area[1] + random.nextFloat() * Math.max(0, area[3] - area[1]);
            float d = tx < 0 ? Float.MAX_VALUE : (float) Math.hypot(x - tx, y - ty);
            if (d > bestD) {
                bestX = x;
                bestY = y;
                bestD = d;
            }
            if (d >= want) break;
        }
        final float nx = bestX, ny = bestY;
        targetVisible = false;
        scheduleStimulus(0, new Stimulus() {
            @Override
            public void show(long onset) {
                tx = nx;
                ty = ny;
                onsetNanos = onset;
                targetVisible = true;
            }
        });
    }

    @Override
    protected void onPause() {
        if (state == State.DONE) {
            finishRound();
        } else if (state == State.PLAYING) {
            state = State.PAUSED;
            targetVisible = false;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Ui.BG);
        if (state == State.INTRO) {
            drawIntro(c);
            return;
        }
        drawHud(c, hits, Ui.TEXT2);
        if (state == State.PAUSED) {
            drawPaused(c);
            return;
        }

        if (targetVisible) {
            fill.setColor(Ui.AIM_TARGET);
            c.drawCircle(tx, ty, radius, fill);
            fill.setColor(Ui.BG);
            c.drawCircle(tx, ty, radius * 0.68f, fill);
            fill.setColor(Ui.AIM_TARGET);
            c.drawCircle(tx, ty, radius * 0.42f, fill);
            fill.setColor(Ui.TEXT);
            c.drawCircle(tx, ty, radius * 0.14f, fill);
        }

        long now = nowNanos();
        boolean animating = false;
        for (int i = effects.size() - 1; i >= 0; i--) {
            Effect e = effects.get(i);
            long age = now - e.start;
            long life = e.hit ? HIT_FX_NANOS : MISS_FX_NANOS;
            if (age >= life) {
                effects.remove(i);
                continue;
            }
            animating = true;
            float p = (float) age / life;
            if (e.hit) {
                stroke.setColor(Ui.withAlpha(Ui.AIM_TARGET, 1 - p));
                stroke.setStrokeWidth(dp(3) * (1 - p) + dp(1));
                c.drawCircle(e.x, e.y, radius * (1 + p), stroke);
            } else {
                fill.setColor(Ui.withAlpha(Ui.STOP, 0.8f * (1 - p)));
                c.drawCircle(e.x, e.y, dp(7), fill);
            }
        }
        if (animating) postInvalidateOnAnimation();

        double mean = runningMean();
        if (!Double.isNaN(mean)) {
            setText(15, Ui.TEXT3, Ui.REGULAR);
            text.setTextAlign(Paint.Align.CENTER);
            drawCentered(c, str(R.string.aim_avg, (int) Math.round(mean)), getWidth() / 2f,
                    getHeight() - safeBottom - dp(24));
        }
    }
}

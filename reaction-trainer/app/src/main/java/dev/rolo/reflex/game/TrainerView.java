package dev.rolo.reflex.game;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Choreographer;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import java.util.Arrays;
import java.util.Random;

import dev.rolo.reflex.Mode;
import dev.rolo.reflex.R;
import dev.rolo.reflex.Session;
import dev.rolo.reflex.Ui;

/**
 * Base for the four drills: precise stimulus timing, touch timestamps, scoring and shared drawing.
 *
 * <p>How a reaction is timed: a stimulus is shown from a {@link Choreographer} frame callback, so
 * we know the vsync time of the exact frame that draws it. That frame can't reach the screen
 * before the next vsync, so the onset is taken as vsync + one refresh period. The response time
 * is the touch event's own timestamp (when the touchscreen reported the finger, not when the app
 * got around to handling it). Both clocks are {@code CLOCK_MONOTONIC}.
 */
public abstract class TrainerView extends View {

    public interface Listener {
        void onRoundFinished(Session session);
    }

    /** Responses faster than this after the stimulus are anticipations, not reactions. */
    protected static final int MIN_REACTION_MS = 100;

    protected final Mode mode;
    protected final int totalTrials;
    protected final Random random = new Random();
    protected final Handler handler = new Handler(Looper.getMainLooper());
    private final Choreographer choreographer = Choreographer.getInstance();
    private Choreographer.FrameCallback pendingStimulus;

    private Listener listener;
    private boolean haptics = true;
    private boolean finished;

    private int[] times = new int[16];
    private int timeCount;
    protected int correct;
    protected int errors;
    protected int misses;
    protected int falseStarts;

    /** Insets of system bars and cutouts, kept clear of HUD text. */
    protected int safeLeft, safeTop, safeRight, safeBottom;

    protected final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    protected TrainerView(Context context, Mode mode, int totalTrials) {
        super(context);
        this.mode = mode;
        this.totalTrials = totalTrials;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        text.setFontFeatureSettings("tnum");
        setFocusable(true);
        setFocusableInTouchMode(true);
        setHapticFeedbackEnabled(true);
        setContentDescription(context.getString(mode.title));
    }

    public static TrainerView create(Context context, Mode mode, int trials) {
        switch (mode) {
            case GO_NO_GO:
                return new GoNoGoView(context, mode, trials);
            case CHOICE:
                return new ChoiceView(context, mode, trials);
            case AIM:
                return new AimView(context, mode, trials);
            case SOUND:
                return new SimpleView(context, mode, trials, new ToneCue());
            case SIMPLE:
            default:
                return new SimpleView(context, mode, trials, null);
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setHaptics(boolean on) {
        haptics = on;
    }

    // ---------------------------------------------------------------- round lifecycle

    /**
     * The app went to the background: drop any pending stimulus so nothing is measured while
     * the screen isn't visible. The trial in progress doesn't count.
     */
    public final void pause() {
        if (finished) return;
        cancelPending();
        onPause();
        invalidate();
    }

    /** Switch to a paused state; a tap should resume via the same path as a normal advance. */
    protected abstract void onPause();

    /** Handle a finger (or key) going down at {@code timeNanos} on the monotonic clock. */
    protected abstract void onTap(float x, float y, long timeNanos);

    /** Whether a hardware key (space, enter, gamepad A) may stand in for a tap at the centre. */
    protected boolean acceptsKeys() {
        return true;
    }

    /** Insets or size changed; recompute layout. */
    protected void onGeometryChanged() {}

    protected final void cancelPending() {
        handler.removeCallbacksAndMessages(null);
        if (pendingStimulus != null) {
            choreographer.removeFrameCallback(pendingStimulus);
            pendingStimulus = null;
        }
    }

    protected final void finishRound() {
        if (finished) return;
        finished = true;
        cancelPending();
        Session s = new Session(mode, System.currentTimeMillis(), Arrays.copyOf(times, timeCount),
                correct, errors, misses, falseStarts);
        if (listener != null) listener.onRoundFinished(s);
    }

    protected final void recordTime(int ms) {
        if (timeCount == times.length) times = Arrays.copyOf(times, times.length * 2);
        times[timeCount++] = ms;
    }

    protected final int recordedCount() {
        return timeCount;
    }

    protected final int recordedBest() {
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < timeCount; i++) best = Math.min(best, times[i]);
        return timeCount == 0 ? -1 : best;
    }

    protected final double runningMean() {
        if (timeCount == 0) return Double.NaN;
        long sum = 0;
        for (int i = 0; i < timeCount; i++) sum += times[i];
        return (double) sum / timeCount;
    }

    // ---------------------------------------------------------------- timing

    protected interface Stimulus {
        /** Called on the frame that draws the stimulus, with its estimated on-screen time. */
        void show(long onsetNanos);
    }

    /** Shows a stimulus on the first frame at least {@code delayMs} from now. */
    protected final void scheduleStimulus(long delayMs, final Stimulus stimulus) {
        if (pendingStimulus != null) choreographer.removeFrameCallback(pendingStimulus);
        pendingStimulus = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                pendingStimulus = null;
                stimulus.show(frameTimeNanos + refreshPeriodNanos());
                invalidate();
            }
        };
        choreographer.postFrameCallbackDelayed(pendingStimulus, Math.max(0, delayMs));
    }

    private long refreshPeriodNanos() {
        Display display = getDisplay();
        float hz = display != null ? display.getRefreshRate() : 60f;
        if (!(hz >= 20f && hz <= 500f)) hz = 60f;
        return (long) (1_000_000_000L / hz);
    }

    /**
     * Now, on the same clock as touch events ({@code uptimeMillis}, i.e. {@code CLOCK_MONOTONIC}).
     * Used for UI timing such as guards and animations; reaction times use frame and event stamps.
     */
    protected static long nowNanos() {
        return SystemClock.uptimeMillis() * 1_000_000L;
    }

    protected static int elapsedMs(long fromNanos, long toNanos) {
        return (int) Math.round((toNanos - fromNanos) / 1_000_000.0);
    }

    protected final long uniformMs(long minMs, long maxMs) {
        return minMs + (long) (random.nextDouble() * (maxMs - minMs));
    }

    // ---------------------------------------------------------------- input

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int i = e.getActionIndex();
            onTap(e.getX(i), e.getY(i), eventNanos(e));
        }
        return true;
    }

    private static long eventNanos(MotionEvent e) {
        if (Build.VERSION.SDK_INT >= 34) return e.getEventTimeNanos();
        return e.getEventTime() * 1_000_000L;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean tapKey = keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A;
        if (tapKey && acceptsKeys()) {
            if (event.getRepeatCount() == 0) {
                onTap(getWidth() / 2f, getHeight() / 2f, event.getEventTime() * 1_000_000L);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- feedback

    protected final void tick() {
        if (haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    protected final void buzz() {
        if (!haptics) return;
        performHapticFeedback(Build.VERSION.SDK_INT >= 30
                ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.LONG_PRESS);
    }

    // ---------------------------------------------------------------- layout

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        int[] i = Ui.safeInsets(insets);
        safeLeft = i[0];
        safeTop = i[1];
        safeRight = i[2];
        safeBottom = i[3];
        onGeometryChanged();
        invalidate();
        return insets;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        onGeometryChanged();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelPending();
        super.onDetachedFromWindow();
    }

    // ---------------------------------------------------------------- drawing helpers

    protected final float dp(float v) {
        return Ui.dp(getContext(), v);
    }

    protected final String str(int res, Object... args) {
        return getContext().getString(res, args);
    }

    protected final void setText(float sizeSp, int color, Typeface face) {
        text.setTextSize(Ui.sp(getContext(), sizeSp));
        text.setColor(color);
        text.setTypeface(face);
        text.setTextAlign(Paint.Align.CENTER);
    }

    /** Draws one line of text, centred horizontally on {@code cx} and vertically on {@code cy}. */
    protected final void drawCentered(Canvas c, String s, float cx, float cy) {
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, text);
    }

    /** Wraps text in the current text style, centred within {@code maxWidth}. */
    protected final StaticLayout wrap(String s, float maxWidth) {
        TextPaint p = new TextPaint(text);
        p.setTextAlign(Paint.Align.LEFT);
        int width = (int) Math.max(1, maxWidth);
        return StaticLayout.Builder.obtain(s, 0, s.length(), p, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0, 1.15f)
                .build();
    }

    protected static void drawLayout(Canvas c, StaticLayout layout, float cx, float top) {
        c.save();
        c.translate(cx - layout.getWidth() / 2f, top);
        layout.draw(c);
        c.restore();
    }

    /** Mode name on the left, progress on the right, a thin progress bar beneath. */
    protected final void drawHud(Canvas c, int done, int textColor) {
        float left = safeLeft + dp(20);
        float right = getWidth() - safeRight - dp(20);
        float baseline = safeTop + dp(36);
        setText(15, textColor, Ui.MEDIUM);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(str(mode.title), left, baseline, text);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(str(R.string.hud_progress, Math.min(done, totalTrials), totalTrials), right, baseline, text);

        float barTop = baseline + dp(12);
        float h = dp(3);
        fill.setColor(Ui.withAlpha(textColor, 0.18f));
        tmp.set(left, barTop, right, barTop + h);
        c.drawRoundRect(tmp, h, h, fill);
        float frac = totalTrials == 0 ? 0 : Math.min(1f, (float) done / totalTrials);
        if (frac > 0) {
            fill.setColor(Ui.withAlpha(textColor, 0.75f));
            tmp.set(left, barTop, left + (right - left) * frac, barTop + h);
            c.drawRoundRect(tmp, h, h, fill);
        }
    }

    /** Height of anything a mode wants drawn on its start card below the trial count. */
    protected float introExtraHeight() {
        return 0;
    }

    /** Draws that extra content, vertically centred on {@code cy}. */
    protected void drawIntroExtra(Canvas c, float cx, float cy) {}

    /**
     * The start card, centred as a block: icon, mode name, how to play, trial count, any
     * mode-specific extra, and a "tap to start" pill.
     */
    protected final void drawIntro(Canvas c) {
        float cx = (safeLeft + getWidth() - safeRight) / 2f;
        float maxW = Math.min(getWidth() - safeLeft - safeRight - dp(48), dp(360));
        float r = dp(36);
        float titleH = dp(36);
        float pillH = dp(52);
        setText(16, Ui.TEXT2, Ui.REGULAR);
        StaticLayout how = wrap(str(mode.howTo), maxW);
        float extra = introExtraHeight();

        float total = 2 * r + dp(20) + titleH + dp(10) + how.getHeight() + dp(14) + dp(20)
                + (extra > 0 ? dp(16) + extra : 0) + dp(36) + pillH;
        float y = Math.max(safeTop + dp(24), (getHeight() - total) / 2f);

        float iconY = y + r;
        fill.setColor(Ui.withAlpha(mode.color, 0.2f));
        c.drawCircle(cx, iconY, r, fill);
        Drawable icon = getContext().getDrawable(mode.icon);
        if (icon != null) {
            int half = Math.round(dp(18));
            icon = icon.mutate();
            icon.setTint(mode.color);
            icon.setBounds(Math.round(cx) - half, Math.round(iconY) - half,
                    Math.round(cx) + half, Math.round(iconY) + half);
            icon.draw(c);
        }
        y += 2 * r + dp(20);

        setText(28, Ui.TEXT, Ui.BOLD);
        drawCentered(c, str(mode.title), cx, y + titleH / 2f);
        y += titleH + dp(10);

        drawLayout(c, how, cx, y);
        y += how.getHeight() + dp(14);

        setText(14, Ui.TEXT3, Ui.REGULAR);
        int countRes = mode == Mode.AIM ? R.plurals.targets : R.plurals.trials;
        drawCentered(c, getResources().getQuantityString(countRes, totalTrials, totalTrials), cx, y + dp(10));
        y += dp(20);

        if (extra > 0) {
            y += dp(16);
            drawIntroExtra(c, cx, y + extra / 2f);
            y += extra;
        }

        y += dp(36);
        drawPill(c, str(R.string.start_hint), cx, y + pillH / 2f);
    }

    protected final void drawPill(Canvas c, String label, float cx, float cy) {
        setText(16, Ui.ON_GO, Ui.MEDIUM);
        float w = text.measureText(label) + dp(48);
        float h = dp(52);
        tmp.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        fill.setColor(Ui.GO);
        c.drawRoundRect(tmp, h / 2f, h / 2f, fill);
        drawCentered(c, label, cx, cy);
    }

    protected final void drawPaused(Canvas c) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        setText(30, Ui.TEXT, Ui.BOLD);
        drawCentered(c, str(R.string.paused), cx, cy - dp(16));
        setText(16, Ui.TEXT2, Ui.REGULAR);
        drawCentered(c, str(R.string.tap_to_continue), cx, cy + dp(22));
    }

    protected final RectF tmpRect() {
        return tmp;
    }
}

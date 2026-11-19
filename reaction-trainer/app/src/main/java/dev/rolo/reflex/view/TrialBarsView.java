package dev.rolo.reflex.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import dev.rolo.reflex.R;
import dev.rolo.reflex.Stats;
import dev.rolo.reflex.Ui;

/**
 * One column per trial, from a zero baseline, with the round average as a hairline. The best
 * trial is labelled; tapping a column shows its own time instead.
 */
public final class TrialBarsView extends View {
    private int[] times = new int[0];
    private int color = Ui.GO;
    private double mean = Double.NaN;
    private int touched = -1;

    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float[] radii = new float[8];

    public TrialBarsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        line.setStrokeWidth(1);
        line.setStyle(Paint.Style.STROKE);
        label.setTextSize(Ui.sp(context, 12));
        label.setFontFeatureSettings("tnum");
        label.setTypeface(Ui.MEDIUM);
        float r = Ui.dp(context, 4); // rounded data-end, square at the baseline
        radii[0] = radii[1] = radii[2] = radii[3] = r;
    }

    public void setData(int[] times, int color) {
        this.times = times.clone();
        this.color = color;
        this.mean = Stats.mean(times);
        touched = -1;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(getContext().getString(R.string.value_ms, times[i]));
        }
        setContentDescription(sb.toString());
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = times.length;
        if (n == 0) return;
        float top = Ui.dp(getContext(), 20);
        float bottom = getHeight() - 1;
        float left = 0;
        float right = getWidth();

        int max = Stats.max(times);
        double step = Stats.niceStep(max / 3.0);
        double yMax = Math.max(step, Math.ceil(max * 1.05 / step) * step);

        float slot = (right - left) / n;
        float gap = Math.max(Ui.dp(getContext(), 2), slot * 0.3f);
        float barW = Math.min(Ui.dp(getContext(), 24), slot - gap);
        int best = Stats.min(times);
        int labelIndex = touched >= 0 ? touched : indexOf(times, best);

        // Baseline.
        line.setColor(Ui.GRID);
        c.drawLine(left, bottom, right, bottom, line);

        for (int i = 0; i < n; i++) {
            float cx = left + slot * (i + 0.5f);
            float h = (float) (times[i] / yMax) * (bottom - top);
            rect.set(cx - barW / 2f, bottom - h, cx + barW / 2f, bottom);
            path.reset();
            path.addRoundRect(rect, radii, Path.Direction.CW);
            bar.setColor(touched >= 0 && i != touched ? Ui.withAlpha(color, 0.45f) : color);
            c.drawPath(path, bar);
        }

        // Average hairline, labelled at the right end.
        float my = bottom - (float) (mean / yMax) * (bottom - top);
        line.setColor(Ui.TEXT3);
        c.drawLine(left, my, right, my, line);
        label.setColor(Ui.TEXT2);
        label.setTextAlign(Paint.Align.RIGHT);
        String avg = getContext().getString(R.string.bars_avg, Math.round(mean));
        float avgW = label.measureText(avg);

        // Value label at the cap of the best (or touched) column.
        float lcx = left + slot * (labelIndex + 0.5f);
        float lh = (float) (times[labelIndex] / yMax) * (bottom - top);
        String value = String.valueOf(times[labelIndex]);
        float vw = label.measureText(value);
        float lx = Math.max(left + vw / 2f, Math.min(right - vw / 2f, lcx));
        float ly = bottom - lh - Ui.dp(getContext(), 5);
        boolean clashes = lx + vw / 2f > right - avgW - Ui.dp(getContext(), 6)
                && Math.abs(ly - (my - Ui.dp(getContext(), 4))) < label.getTextSize();
        c.drawText(avg, right, clashes ? my + label.getTextSize() + Ui.dp(getContext(), 2)
                : my - Ui.dp(getContext(), 4), label);

        label.setColor(Ui.TEXT);
        label.setTextAlign(Paint.Align.CENTER);
        c.drawText(value, lx, ly, label);
    }

    private static int indexOf(int[] v, int x) {
        for (int i = 0; i < v.length; i++) if (v[i] == x) return i;
        return 0;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (times.length == 0) return false;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            float slot = (float) getWidth() / times.length;
            int i = (int) (e.getX() / slot);
            touched = Math.max(0, Math.min(times.length - 1, i));
            if (action == MotionEvent.ACTION_MOVE) getParent().requestDisallowInterceptTouchEvent(true);
            invalidate();
            return true;
        }
        return action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
    }
}

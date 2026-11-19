package dev.rolo.reflex.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.List;

import dev.rolo.reflex.R;
import dev.rolo.reflex.Session;
import dev.rolo.reflex.Stats;
import dev.rolo.reflex.Ui;

/**
 * Average reaction time per round, as a single 2px line. Only the latest and the best rounds are
 * labelled; dragging across the chart shows any round's details.
 */
public final class TrendChartView extends View {
    private Session[] data = new Session[0];
    /** Round number (1-based, over the whole history) of data[0]. */
    private int firstRound = 1;
    private int color = Ui.GO;
    private boolean showAccuracy;
    private int touched = -1;
    private float downX, downY;
    private boolean dragging;
    private final int touchSlop;

    private double yMin, yMax, yStep;
    private final RectF plot = new RectF();

    private final Paint grid = new Paint();
    private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private final Runnable clearTouch = new Runnable() {
        @Override
        public void run() {
            touched = -1;
            invalidate();
        }
    };

    public TrendChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        grid.setColor(Ui.GRID);
        grid.setStrokeWidth(1);
        axis.setColor(Ui.TEXT3);
        axis.setTextSize(Ui.sp(context, 11));
        axis.setFontFeatureSettings("tnum");
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Ui.dp(context, 2));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        ring.setColor(Ui.BG);
        tipBg.setColor(Ui.SURFACE2);
        tipText.setTextSize(Ui.sp(context, 12));
        tipText.setFontFeatureSettings("tnum");
    }

    /** @param rounds the rounds to plot, oldest first; @param firstRound the number of the first one. */
    public void setData(List<Session> rounds, int firstRound, int color, boolean showAccuracy) {
        this.data = rounds.toArray(new Session[0]);
        this.firstRound = firstRound;
        this.color = color;
        this.showAccuracy = showAccuracy;
        touched = -1;

        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Session s : data) {
            lo = Math.min(lo, s.mean());
            hi = Math.max(hi, s.mean());
        }
        if (data.length == 0) {
            lo = 0;
            hi = 100;
        }
        if (hi - lo < 20) { // a flat line still gets a readable band
            double mid = (hi + lo) / 2;
            lo = mid - 10;
            hi = mid + 10;
        }
        yStep = Stats.niceStep((hi - lo) / 3);
        yMin = Math.max(0, Math.floor(lo / yStep) * yStep);
        yMax = Math.ceil(hi / yStep) * yStep;
        if (yMax <= yMin) yMax = yMin + yStep;

        if (data.length > 0) {
            Session last = data[data.length - 1];
            Session best = Stats.personalBest(rounds);
            setContentDescription(getContext().getString(R.string.chart_desc, data.length,
                    Math.round(last.mean()), best != null ? Math.round(best.mean()) : Math.round(last.mean())));
        }
        invalidate();
    }

    private float xAt(int i) {
        if (data.length == 1) return plot.centerX();
        return plot.left + plot.width() * i / (data.length - 1);
    }

    private float yAt(double v) {
        return (float) (plot.bottom - (v - yMin) / (yMax - yMin) * plot.height());
    }

    @Override
    protected void onDraw(Canvas c) {
        if (data.length == 0) return;
        Context ctx = getContext();
        float dp = Ui.dp(ctx, 1);

        // Room on the left for tick labels, below for dates, above for the tooltip and labels.
        axis.setTextAlign(Paint.Align.RIGHT);
        float labelW = axis.measureText(String.valueOf((int) yMax));
        plot.set(labelW + 10 * dp, 26 * dp, getWidth() - 14 * dp, getHeight() - 22 * dp);

        // Gridlines and y ticks.
        Paint.FontMetrics fm = axis.getFontMetrics();
        for (double v = yMin; v <= yMax + 1e-6; v += yStep) {
            float y = yAt(v);
            c.drawLine(plot.left, y, plot.right, y, grid);
            c.drawText(String.valueOf((int) Math.round(v)), plot.left - 6 * dp, y - (fm.ascent + fm.descent) / 2, axis);
        }

        // First and last dates.
        float dateY = getHeight() - 4 * dp;
        axis.setTextAlign(Paint.Align.LEFT);
        c.drawText(shortDate(data[0].timestamp), plot.left, dateY, axis);
        if (data.length > 1) {
            axis.setTextAlign(Paint.Align.RIGHT);
            c.drawText(shortDate(data[data.length - 1].timestamp), plot.right, dateY, axis);
        }

        // The line.
        line.setColor(color);
        path.reset();
        for (int i = 0; i < data.length; i++) {
            float x = xAt(i);
            float y = yAt(data[i].mean());
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        if (data.length > 1) c.drawPath(path, line);

        // Label only the latest round and the best one.
        int last = data.length - 1;
        int best = -1;
        for (int i = 0; i < data.length; i++) {
            if (!data[i].qualifies()) continue;
            if (best < 0 || data[i].mean() < data[best].mean()) best = i;
        }
        if (best >= 0 && best != last) {
            drawDot(c, best, 4 * dp);
            label(c, best, ctx.getString(R.string.chart_best, Math.round(data[best].mean())), false);
        }
        drawDot(c, last, 4 * dp);
        label(c, last, String.valueOf(Math.round(data[last].mean())), true);

        if (touched >= 0 && touched < data.length) drawTooltip(c, touched);
    }

    private void drawDot(Canvas c, int i, float r) {
        float x = xAt(i);
        float y = yAt(data[i].mean());
        float ringW = Ui.dp(getContext(), 2);
        c.drawCircle(x, y, r + ringW, ring);
        dot.setColor(color);
        c.drawCircle(x, y, r, dot);
    }

    private void label(Canvas c, int i, String s, boolean primary) {
        float x = xAt(i);
        float y = yAt(data[i].mean());
        axis.setColor(primary ? Ui.TEXT : Ui.TEXT2);
        float w = axis.measureText(s);
        axis.setTextAlign(Paint.Align.CENTER);
        float lx = Math.max(plot.left + w / 2, Math.min(getWidth() - w / 2, x));
        float above = y - Ui.dp(getContext(), 10);
        float ly = above - axis.getTextSize() < 0 ? y + Ui.dp(getContext(), 18) : above;
        c.drawText(s, lx, ly, axis);
        axis.setColor(Ui.TEXT3);
    }

    private void drawTooltip(Canvas c, int i) {
        Context ctx = getContext();
        float dp = Ui.dp(ctx, 1);
        float x = xAt(i);
        c.drawLine(x, plot.top, x, plot.bottom, grid);
        drawDot(c, i, 5 * dp);

        Session s = data[i];
        String l1 = ctx.getString(R.string.chart_round, firstRound + i, shortDate(s.timestamp));
        String l2 = showAccuracy
                ? ctx.getString(R.string.round_row_accuracy, Math.round(s.mean()), Math.round(s.accuracy() * 100))
                : ctx.getString(R.string.value_ms, Math.round(s.mean()));
        tipText.setTypeface(Ui.REGULAR);
        float w1 = tipText.measureText(l1);
        tipText.setTypeface(Ui.MEDIUM);
        float w2 = tipText.measureText(l2);
        float pad = 8 * dp;
        float w = Math.max(w1, w2) + 2 * pad;
        float lineH = tipText.getTextSize() * 1.3f;
        float h = 2 * lineH + 2 * pad - (lineH - tipText.getTextSize());
        float left = Math.max(0, Math.min(getWidth() - w, x - w / 2));
        rect.set(left, 0, left + w, h);
        c.drawRoundRect(rect, 8 * dp, 8 * dp, tipBg);
        tipText.setTextAlign(Paint.Align.LEFT);
        tipText.setTypeface(Ui.REGULAR);
        tipText.setColor(Ui.TEXT2);
        c.drawText(l1, left + pad, pad + tipText.getTextSize() * 0.9f, tipText);
        tipText.setTypeface(Ui.MEDIUM);
        tipText.setColor(Ui.TEXT);
        c.drawText(l2, left + pad, pad + tipText.getTextSize() * 0.9f + lineH, tipText);
    }

    private String shortDate(long ms) {
        return DateUtils.formatDateTime(getContext(), ms, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH);
    }

    private int nearest(float x) {
        if (data.length <= 1) return 0;
        float t = (x - plot.left) / plot.width();
        return Math.max(0, Math.min(data.length - 1, Math.round(t * (data.length - 1))));
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (data.length == 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                removeCallbacks(clearTouch);
                downX = e.getX();
                downY = e.getY();
                dragging = false;
                touched = nearest(e.getX());
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                // Horizontal drags scrub the chart; vertical ones are left to the page scroll.
                float dx = Math.abs(e.getX() - downX);
                float dy = Math.abs(e.getY() - downY);
                if (!dragging && dx > touchSlop && dx > dy) {
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                touched = nearest(e.getX());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                postDelayed(clearTouch, 2500);
                return true;
            default:
                return true;
        }
    }
}

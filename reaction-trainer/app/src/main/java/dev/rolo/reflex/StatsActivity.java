package dev.rolo.reflex;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

import dev.rolo.reflex.view.TrendChartView;

/** Progress per mode: headline numbers, a trend chart and the recent rounds. */
public final class StatsActivity extends Activity {
    private static final String STATE_MODE = "mode";
    private static final int CHART_ROUNDS = 50;
    private static final int LIST_ROUNDS = 20;

    private final EnumMap<Mode, TextView> chips = new EnumMap<>(Mode.class);
    private Mode current = Mode.SIMPLE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats);
        Ui.edgeToEdge(this, findViewById(R.id.root));
        if (savedInstanceState != null) {
            Mode saved = Mode.fromId(savedInstanceState.getString(STATE_MODE, ""));
            if (saved != null) current = saved;
        }

        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        LinearLayout chipRow = findViewById(R.id.chips);
        int gap = Math.round(Ui.dp(this, 8));
        int padH = Math.round(Ui.dp(this, 14));
        for (final Mode mode : Mode.values()) {
            TextView chip = new TextView(this);
            chip.setText(mode.title);
            chip.setTextColor(Ui.TEXT);
            chip.setTextSize(14);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setMinHeight(Math.round(Ui.dp(this, 40)));
            chip.setPadding(padH, 0, padH, 0);
            chip.setBackgroundResource(R.drawable.bg_chip);
            Drawable dot = getDrawable(R.drawable.dot).mutate();
            dot.setTintList(ColorStateList.valueOf(mode.color));
            chip.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
            chip.setCompoundDrawablePadding(Math.round(Ui.dp(this, 8)));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    show(mode);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (chipRow.getChildCount() > 0) lp.setMarginStart(gap);
            chipRow.addView(chip, lp);
            chips.put(mode, chip);
        }

        findViewById(R.id.clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClear();
            }
        });
        show(current);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_MODE, current.id);
    }

    private void show(Mode mode) {
        current = mode;
        for (Mode m : Mode.values()) {
            TextView chip = chips.get(m);
            chip.setSelected(m == mode);
            chip.setTextColor(m == mode ? Ui.TEXT : Ui.TEXT2);
        }
        final TextView selected = chips.get(mode);
        selected.post(new Runnable() {
            @Override
            public void run() {
                selected.requestRectangleOnScreen(new Rect(0, 0, selected.getWidth(), selected.getHeight()), false);
            }
        });

        List<Session> history = HistoryStore.get(this).forMode(mode);
        TextView empty = findViewById(R.id.empty);
        View content = findViewById(R.id.content);
        if (history.isEmpty()) {
            empty.setText(getString(R.string.stats_empty, getString(mode.title)));
            empty.setVisibility(View.VISIBLE);
            content.setVisibility(View.GONE);
            return;
        }
        empty.setVisibility(View.GONE);
        content.setVisibility(View.VISIBLE);

        Session best = Stats.personalBest(history);
        List<String[]> tiles = new ArrayList<>();
        tiles.add(new String[] {String.valueOf(history.size()), getString(R.string.stats_rounds)});
        tiles.add(new String[] {best != null ? Ui.ms(this, best.mean()) : getString(R.string.value_none),
                getString(R.string.stats_best_avg)});
        tiles.add(new String[] {Ui.ms(this, Stats.recentAverage(history, 10)), getString(R.string.stats_recent)});
        if (mode.scoresAccuracy()) {
            tiles.add(new String[] {Ui.percent(this, Stats.recentAccuracy(history, 10)),
                    getString(R.string.stats_accuracy)});
        } else {
            tiles.add(new String[] {Ui.ms(this, Stats.fastestSingle(history)), getString(R.string.stats_fastest)});
        }
        Ui.tiles((LinearLayout) findViewById(R.id.tiles), tiles);

        int from = Math.max(0, history.size() - CHART_ROUNDS);
        List<Session> recent = history.subList(from, history.size());
        ((TextView) findViewById(R.id.chart_sub)).setText(getString(R.string.stats_chart_sub, recent.size()));
        ((TrendChartView) findViewById(R.id.chart)).setData(recent, from + 1, mode.color, mode.scoresAccuracy());

        LinearLayout list = findViewById(R.id.recent);
        list.removeAllViews();
        for (int i = history.size() - 1; i >= Math.max(0, history.size() - LIST_ROUNDS); i--) {
            Session s = history.get(i);
            View row = getLayoutInflater().inflate(R.layout.item_round, list, false);
            ((TextView) row.findViewById(R.id.when)).setText(DateUtils.formatDateTime(this, s.timestamp,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH
                            | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY));
            ((TextView) row.findViewById(R.id.value)).setText(mode.scoresAccuracy()
                    ? getString(R.string.round_row_accuracy, Math.round(s.mean()), Math.round(s.accuracy() * 100))
                    : getString(R.string.value_ms, Math.round(s.mean())));
            list.addView(row);
        }

        ((Button) findViewById(R.id.clear)).setText(getString(R.string.stats_clear, getString(mode.title)));
    }

    private void confirmClear() {
        final Mode mode = current;
        new AlertDialog.Builder(this)
                .setTitle(R.string.stats_clear_title)
                .setMessage(getString(R.string.stats_clear_body, getString(mode.title)))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        HistoryStore.get(StatsActivity.this).clear(mode);
                        show(mode);
                    }
                })
                .show();
    }
}

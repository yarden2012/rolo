package dev.rolo.reflex;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.TimeZone;

/** Home: a quick summary, the four modes with your best in each, and links to stats and settings. */
public final class MainActivity extends Activity {
    private final EnumMap<Mode, TextView> metaViews = new EnumMap<>(Mode.class);
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Ui.edgeToEdge(this, findViewById(R.id.root));
        prefs = new Prefs(this);

        LinearLayout list = findViewById(R.id.modes);
        LayoutInflater inflater = getLayoutInflater();
        for (final Mode mode : Mode.values()) {
            View item = inflater.inflate(R.layout.item_mode, list, false);
            ImageView icon = item.findViewById(R.id.icon);
            icon.setImageResource(mode.icon);
            icon.setImageTintList(ColorStateList.valueOf(mode.color));
            icon.setBackgroundTintList(ColorStateList.valueOf(Ui.withAlpha(mode.color, 0.18f)));
            ((TextView) item.findViewById(R.id.title)).setText(mode.title);
            ((TextView) item.findViewById(R.id.desc)).setText(mode.description);
            metaViews.put(mode, (TextView) item.findViewById(R.id.meta));
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, GameActivity.class)
                            .putExtra(GameActivity.EXTRA_MODE, mode.id));
                }
            });
            list.addView(item);
        }

        findViewById(R.id.stats).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, StatsActivity.class));
            }
        });
        findViewById(R.id.settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        HistoryStore store = HistoryStore.get(this);
        List<Session> all = store.all();

        int fastest = Stats.fastestSingle(store.forMode(Mode.SIMPLE));
        List<String[]> tiles = new ArrayList<>();
        tiles.add(new String[] {String.valueOf(all.size()), getString(R.string.summary_rounds)});
        tiles.add(new String[] {Ui.ms(this, fastest), getString(R.string.summary_fastest)});
        tiles.add(new String[] {String.valueOf(Stats.dayStreak(all, System.currentTimeMillis(), TimeZone.getDefault())),
                getString(R.string.summary_streak)});
        LinearLayout summary = findViewById(R.id.summary);
        summaryRow(summary, tiles);

        for (Mode mode : Mode.values()) {
            List<Session> history = store.forMode(mode);
            TextView meta = metaViews.get(mode);
            if (history.isEmpty()) {
                meta.setText(R.string.mode_meta_new);
                continue;
            }
            String rounds = getResources().getQuantityString(R.plurals.rounds, history.size(), history.size());
            Session best = Stats.personalBest(history);
            meta.setText(best != null
                    ? getString(R.string.mode_meta, Math.round(best.mean()), rounds)
                    : getString(R.string.mode_meta_unranked, rounds));
        }
    }

    /** Three equal tiles in one row. */
    private void summaryRow(LinearLayout container, List<String[]> entries) {
        container.removeAllViews();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        container.addView(row);
        int gap = Math.round(Ui.dp(this, 10));
        for (int i = 0; i < entries.size(); i++) {
            View tile = getLayoutInflater().inflate(R.layout.item_tile, row, false);
            ((TextView) tile.findViewById(R.id.value)).setText(entries.get(i)[0]);
            ((TextView) tile.findViewById(R.id.label)).setText(entries.get(i)[1]);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tile.getLayoutParams();
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT; // equal heights even if a label wraps
            if (i > 0) lp.setMarginStart(gap);
            row.addView(tile);
        }
    }

    private void showSettings() {
        View view = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        RadioGroup group = view.findViewById(R.id.length_group);
        int[] ids = {R.id.length_short, R.id.length_standard, R.id.length_long};
        group.check(ids[Math.max(0, Math.min(2, prefs.roundLength()))]);
        group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int checkedId) {
                prefs.setRoundLength(checkedId == R.id.length_short ? Prefs.LENGTH_SHORT
                        : checkedId == R.id.length_long ? Prefs.LENGTH_LONG : Prefs.LENGTH_STANDARD);
            }
        });
        Switch haptics = view.findViewById(R.id.haptics);
        haptics.setChecked(prefs.haptics());
        haptics.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setHaptics(on);
            }
        });
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings)
                .setView(view)
                .setPositiveButton(R.string.done, null)
                .show();
    }
}

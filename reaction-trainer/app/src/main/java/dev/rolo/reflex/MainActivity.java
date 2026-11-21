package dev.rolo.reflex;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.TimeZone;

import dev.rolo.reflex.game.CustomCue;

/** Home: a quick summary, the four modes with your best in each, and links to stats and settings. */
public final class MainActivity extends Activity {
    private static final int REQUEST_CUE = 1;

    private final EnumMap<Mode, TextView> metaViews = new EnumMap<>(Mode.class);
    private Prefs prefs;
    /** The settings dialog's "current sound" line, while the dialog is open. */
    private TextView cueName;

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
        cueName = view.findViewById(R.id.cue_name);
        showCueName();
        view.findViewById(R.id.cue_choose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // The system picker needs no storage permission; we only read the one file.
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("audio/*");
                try {
                    startActivityForResult(pick, REQUEST_CUE);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, R.string.cue_failed, Toast.LENGTH_SHORT).show();
                }
            }
        });
        view.findViewById(R.id.cue_play).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CustomCue.preview(MainActivity.this);
            }
        });
        view.findViewById(R.id.cue_reset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CustomCue.stopPreview();
                CustomCue.clear(MainActivity.this);
                showCueName();
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings)
                .setView(view)
                .setPositiveButton(R.string.done, null)
                .create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                CustomCue.stopPreview();
                cueName = null;
            }
        });
        dialog.show();
    }

    private void showCueName() {
        if (cueName == null) return;
        String name = CustomCue.name(this);
        cueName.setText(name != null ? name : getString(R.string.cue_beep));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CUE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final String name = displayName(uri);
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok;
                try {
                    CustomCue.importFrom(app, uri, name);
                    ok = true;
                } catch (IOException | RuntimeException e) {
                    Log.w("MainActivity", "Could not import " + uri, e);
                    ok = false;
                }
                final boolean done = ok;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (done) {
                            showCueName();
                            Toast.makeText(MainActivity.this, getString(R.string.cue_set, name), Toast.LENGTH_SHORT).show();
                            CustomCue.preview(MainActivity.this); // let them hear it straight away
                        } else {
                            Toast.makeText(MainActivity.this, R.string.cue_failed, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "Reflex-import").start();
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (RuntimeException ignored) {
            // fall through to a generic name
        }
        return getString(R.string.cue_custom);
    }
}

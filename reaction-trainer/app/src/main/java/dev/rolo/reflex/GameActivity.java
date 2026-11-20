package dev.rolo.reflex;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import dev.rolo.reflex.game.TrainerView;
import dev.rolo.reflex.view.TrialBarsView;

/** Runs one drill full-screen, then shows the round's results. */
public final class GameActivity extends Activity implements TrainerView.Listener {
    public static final String EXTRA_MODE = "mode";

    private Mode mode;
    private Prefs prefs;
    private FrameLayout root;
    private TrainerView game;
    private View results;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mode = Mode.fromId(getIntent().getStringExtra(EXTRA_MODE));
        if (mode == null) {
            finish();
            return;
        }
        prefs = new Prefs(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);
        setVolumeControlStream(AudioManager.STREAM_MUSIC); // volume keys set the beep's volume
        Ui.immersive(this);
        startRound();
    }

    private void startRound() {
        if (game != null) root.removeView(game);
        game = TrainerView.create(this, mode, mode.trials(prefs.roundLength()));
        game.setHaptics(prefs.haptics());
        game.setListener(this);
        root.addView(game, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (results != null) results.setVisibility(View.GONE);
        game.requestFocus();
        game.requestApplyInsets();
    }

    @Override
    public void onRoundFinished(Session session) {
        HistoryStore store = HistoryStore.get(this);
        List<Session> before = store.forMode(mode);
        Session previousBest = Stats.personalBest(before);
        double recent = Stats.recentAverage(before, 10);
        if (session.hasTimes()) store.add(session);
        showResults(session, previousBest, recent);
    }

    private void showResults(Session s, Session previousBest, double recent) {
        if (results == null) {
            results = getLayoutInflater().inflate(R.layout.results, root, false);
            Ui.padForSystemBars(results);
            root.addView(results);
            results.findViewById(R.id.again).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startRound();
                }
            });
            results.findViewById(R.id.done).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }
        results.setVisibility(View.VISIBLE);
        results.scrollTo(0, 0);
        if (game != null) {
            // We're inside the game view's own touch or frame callback: detach it afterwards.
            final TrainerView finished = game;
            game = null;
            root.post(new Runnable() {
                @Override
                public void run() {
                    root.removeView(finished);
                }
            });
        }

        TextView modeLabel = results.findViewById(R.id.mode);
        modeLabel.setText(mode.title);
        Drawable dot = modeLabel.getCompoundDrawablesRelative()[0];
        if (dot != null) dot.mutate().setTintList(ColorStateList.valueOf(mode.color));

        TextView value = results.findViewById(R.id.value);
        TextView unit = results.findViewById(R.id.unit);
        TextView badge = results.findViewById(R.id.badge);
        TextView compare = results.findViewById(R.id.compare);
        View trialsBlock = results.findViewById(R.id.trials_block);

        if (!s.hasTimes()) {
            value.setText(R.string.value_none);
            unit.setText(R.string.results_no_times);
            badge.setVisibility(View.GONE);
            compare.setVisibility(View.GONE);
            trialsBlock.setVisibility(View.GONE);
        } else {
            double mean = s.mean();
            value.setText(String.valueOf(Math.round(mean)));
            unit.setText(mode == Mode.AIM ? R.string.results_unit_target : R.string.results_unit_avg);

            if (s.qualifies() && (previousBest == null || mean < previousBest.mean())) {
                badge.setText(previousBest == null ? R.string.badge_baseline : R.string.badge_pb);
                badge.setVisibility(View.VISIBLE);
            } else {
                badge.setVisibility(View.GONE);
            }

            if (!Double.isNaN(recent)) {
                long diff = Math.round(mean - recent);
                if (diff <= -1) compare.setText(getString(R.string.compare_faster, -diff));
                else if (diff >= 1) compare.setText(getString(R.string.compare_slower, diff));
                else compare.setText(R.string.compare_same);
                compare.setVisibility(View.VISIBLE);
            } else {
                compare.setVisibility(View.GONE);
            }

            trialsBlock.setVisibility(View.VISIBLE);
            ((TrialBarsView) results.findViewById(R.id.bars)).setData(s.times(), mode.color);
        }

        Ui.tiles((LinearLayout) results.findViewById(R.id.tiles), tilesFor(s));
    }

    private List<String[]> tilesFor(Session s) {
        List<String[]> t = new ArrayList<>();
        String best = Ui.ms(this, s.best());
        String median = Ui.ms(this, s.median());
        String consistency = s.count() >= 2
                ? getString(R.string.value_sd, (int) Math.round(s.sd())) : getString(R.string.value_none);
        String accuracy = Ui.percent(this, s.attempts() == 0 ? Double.NaN : s.accuracy());
        switch (mode) {
            case SIMPLE:
            case SOUND:
                t.add(new String[] {best, getString(R.string.tile_best)});
                t.add(new String[] {median, getString(R.string.tile_median)});
                t.add(new String[] {consistency, getString(R.string.tile_consistency)});
                t.add(new String[] {count(s.falseStarts), getString(R.string.tile_false_starts)});
                break;
            case GO_NO_GO:
                t.add(new String[] {accuracy, getString(R.string.tile_accuracy)});
                t.add(new String[] {best, getString(R.string.tile_best)});
                t.add(new String[] {count(s.errors), getString(R.string.tile_wrong_taps)});
                t.add(new String[] {count(s.misses), getString(R.string.tile_missed_greens)});
                break;
            case CHOICE:
                t.add(new String[] {accuracy, getString(R.string.tile_accuracy)});
                t.add(new String[] {best, getString(R.string.tile_best)});
                t.add(new String[] {count(s.errors), getString(R.string.tile_wrong_pads)});
                t.add(new String[] {consistency, getString(R.string.tile_consistency)});
                break;
            case AIM:
                t.add(new String[] {accuracy, getString(R.string.tile_accuracy)});
                t.add(new String[] {best, getString(R.string.tile_fastest)});
                t.add(new String[] {count(s.errors), getString(R.string.tile_missed_taps)});
                t.add(new String[] {median, getString(R.string.tile_median)});
                break;
        }
        return t;
    }

    private String count(int n) {
        return getString(R.string.value_count, n);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (game != null) game.pause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.immersive(this);
    }
}

package dev.rolo.reflex;

import static android.os.Looper.getMainLooper;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.time.Duration;
import java.util.List;

import dev.rolo.reflex.game.TrainerView;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w360dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AppTest {
    private Context context;

    @Before
    public void setUp() {
        HistoryStore.resetForTests();
        context = RuntimeEnvironment.getApplication();
    }

    @After
    public void tearDown() {
        HistoryStore.resetForTests();
    }

    @Test
    public void homeListsEveryModeAndOpensIt() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        LinearLayout modes = a.findViewById(R.id.modes);
        assertEquals(Mode.values().length, modes.getChildCount());
        View goNoGo = modes.getChildAt(Mode.GO_NO_GO.ordinal());
        assertEquals(a.getString(R.string.mode_gonogo),
                ((TextView) goNoGo.findViewById(R.id.title)).getText().toString());
        assertEquals(a.getString(R.string.mode_meta_new),
                ((TextView) goNoGo.findViewById(R.id.meta)).getText().toString());

        goNoGo.performClick();
        Intent next = shadowOf(a).getNextStartedActivity();
        assertEquals(GameActivity.class.getName(), next.getComponent().getClassName());
        assertEquals(Mode.GO_NO_GO.id, next.getStringExtra(GameActivity.EXTRA_MODE));
    }

    @Test
    public void homeShowsBestsFromHistory() {
        HistoryStore.get(context).add(new Session(Mode.SIMPLE, System.currentTimeMillis(),
                new int[] {240, 250, 260}, 3, 0, 0, 0));
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        LinearLayout modes = a.findViewById(R.id.modes);
        String meta = ((TextView) modes.getChildAt(0).findViewById(R.id.meta)).getText().toString();
        assertTrue(meta, meta.contains("250"));
    }

    @Test
    public void playingARoundShowsResultsAndSavesIt() throws Exception {
        new Prefs(context).setRoundLength(Prefs.LENGTH_SHORT); // 5 trials
        Intent intent = new Intent(context, GameActivity.class).putExtra(GameActivity.EXTRA_MODE, Mode.SIMPLE.id);
        ActivityController<GameActivity> controller = Robolectric.buildActivity(GameActivity.class, intent).setup();
        GameActivity a = controller.get();
        idle(50);
        ViewGroup root = (ViewGroup) ((ViewGroup) a.findViewById(android.R.id.content)).getChildAt(0);
        TrainerView game = (TrainerView) root.getChildAt(0);

        tap(game);
        for (int i = 0; i < 5; i++) {
            for (int waited = 0; pixel(game, 5, game.getHeight() / 2) != Ui.GO; waited += 5) {
                assertTrue("stimulus shown", waited < 6000);
                idle(5);
            }
            idle(230);
            tap(game);
            idle(300);
            tap(game);
        }
        idle(50);

        TextView value = a.findViewById(R.id.value);
        assertNotNull("results are showing", value);
        assertEquals(View.VISIBLE, ((View) value.getParent().getParent()).getVisibility());
        int shown = Integer.parseInt(value.getText().toString());
        assertTrue("average " + shown, shown >= 190 && shown <= 230);
        assertEquals(a.getString(R.string.badge_baseline), ((TextView) a.findViewById(R.id.badge)).getText().toString());

        List<Session> saved = HistoryStore.get(context).forMode(Mode.SIMPLE);
        assertEquals(1, saved.size());
        assertEquals(5, saved.get(0).count());

        // It reached disk: a fresh store reads it back.
        HistoryStore.get(context).awaitWritesForTests();
        HistoryStore.resetForTests();
        List<Session> reloaded = HistoryStore.get(context).forMode(Mode.SIMPLE);
        assertEquals(1, reloaded.size());
        assertEquals(saved.get(0).mean(), reloaded.get(0).mean(), 1e-9);

        // Play again starts a fresh round.
        a.findViewById(R.id.again).performClick();
        idle(50);
        assertTrue(root.getChildAt(0) instanceof TrainerView);
        assertTrue(root.getChildAt(0) != game);
    }

    @Test
    public void statsShowHistoryAndClear() {
        HistoryStore store = HistoryStore.get(context);
        long now = System.currentTimeMillis();
        for (int i = 0; i < 12; i++) {
            store.add(new Session(Mode.CHOICE, now - (12 - i) * 3_600_000L,
                    new int[] {400 - i * 5, 410 - i * 5, 420 - i * 5}, 3, i % 3 == 0 ? 1 : 0, 0, 0));
        }
        Intent intent = new Intent(context, StatsActivity.class);
        StatsActivity a = Robolectric.buildActivity(StatsActivity.class, intent).setup().get();
        LinearLayout chips = a.findViewById(R.id.chips);
        assertEquals(Mode.values().length, chips.getChildCount());

        // Tap reaction has no rounds yet.
        assertEquals(View.VISIBLE, a.findViewById(R.id.empty).getVisibility());

        chips.getChildAt(Mode.CHOICE.ordinal()).performClick();
        assertEquals(View.GONE, a.findViewById(R.id.empty).getVisibility());
        assertEquals(View.VISIBLE, a.findViewById(R.id.content).getVisibility());
        assertEquals(12, ((LinearLayout) a.findViewById(R.id.recent)).getChildCount());
        assertNotNull(a.findViewById(R.id.chart).getContentDescription());

        store.clear(Mode.CHOICE);
        assertEquals(0, store.forMode(Mode.CHOICE).size());
    }

    private static void idle(long ms) {
        shadowOf(getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private static Bitmap frame;

    /** Draws into one reused bitmap (bitmaps are native memory the GC can't see). */
    private static int pixel(View v, float x, float y) {
        if (frame == null || frame.getWidth() != v.getWidth() || frame.getHeight() != v.getHeight()) {
            frame = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        }
        frame.eraseColor(0);
        v.draw(new Canvas(frame));
        return frame.getPixel((int) x, (int) y);
    }

    private static void tap(View v) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, v.getWidth() / 2f, v.getHeight() / 2f, 0);
        MotionEvent up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, v.getWidth() / 2f, v.getHeight() / 2f, 0);
        v.dispatchTouchEvent(down);
        v.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }
}

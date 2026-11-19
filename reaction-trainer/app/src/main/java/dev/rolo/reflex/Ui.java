package dev.rolo.reflex;

import android.app.Activity;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Shared colors, type and window helpers. */
public final class Ui {
    // Mirrors res/values/colors.xml, for canvas drawing.
    public static final int BG = 0xFF0E1116;
    public static final int SURFACE = 0xFF161A21;
    public static final int SURFACE2 = 0xFF1F2530;
    public static final int GRID = 0xFF2A313D;
    public static final int TEXT = 0xFFECEFF4;
    public static final int TEXT2 = 0xFFA4ADBD;
    public static final int TEXT3 = 0xFF6B7587;

    // Stimulus colors. These are big, bright fills, so they also differ sharply in
    // lightness from what precedes them: the cue never depends on telling hues apart.
    public static final int GO = 0xFF2BD97C;
    public static final int ON_GO = 0xFF052A19;
    public static final int STOP = 0xFFFF5A5F;
    public static final int WAIT = 0xFF57161C;
    public static final int WARN_BG = 0xFF4A3410;
    public static final int CHOICE_LIT = 0xFF6AAAFF;
    public static final int AIM_TARGET = 0xFFFF7A45;

    private Ui() {}

    public static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    public static float sp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.getResources().getDisplayMetrics());
    }

    public static int withAlpha(int color, float alpha) {
        int a = Math.round(Math.max(0, Math.min(1, alpha)) * 255);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    public static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    public static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    public static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    public static final Typeface BLACK = Typeface.create("sans-serif-black", Typeface.NORMAL);

    /**
     * Draws the activity behind the status and navigation bars on every Android version
     * (Android 15 insists on it anyway) and pads {@code root} so its content clears them.
     */
    @SuppressWarnings("deprecation")
    public static void edgeToEdge(Activity activity, View root) {
        Window window = activity.getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        padForSystemBars(root);
    }

    /** Keeps the screen on and hides the system bars; a swipe from the edge brings them back briefly. */
    @SuppressWarnings("deprecation")
    public static void immersive(Activity activity) {
        Window window = activity.getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /**
     * Adds the system bar and cutout insets to {@code view}'s own padding. Bars count even
     * while hidden, so content doesn't jump when they are swiped in.
     */
    public static void padForSystemBars(View view) {
        final int left = view.getPaddingLeft();
        final int top = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int bottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int[] i = safeInsets(insets);
                v.setPadding(left + i[0], top + i[1], right + i[2], bottom + i[3]);
                return insets;
            }
        });
        view.requestApplyInsets();
    }

    /** Left, top, right, bottom insets of the system bars and display cutout, visible or not. */
    @SuppressWarnings("deprecation")
    public static int[] safeInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            Insets i = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            return new int[] {i.left, i.top, i.right, i.bottom};
        }
        int[] out = {insets.getStableInsetLeft(), insets.getStableInsetTop(),
                insets.getStableInsetRight(), insets.getStableInsetBottom()};
        if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
            android.view.DisplayCutout cut = insets.getDisplayCutout();
            out[0] = Math.max(out[0], cut.getSafeInsetLeft());
            out[1] = Math.max(out[1], cut.getSafeInsetTop());
            out[2] = Math.max(out[2], cut.getSafeInsetRight());
            out[3] = Math.max(out[3], cut.getSafeInsetBottom());
        }
        return out;
    }

    /** Fills {@code container} with stat tiles, two per row. Each entry is {value, label}. */
    public static void tiles(LinearLayout container, List<String[]> entries) {
        container.removeAllViews();
        Context c = container.getContext();
        LayoutInflater inflater = LayoutInflater.from(c);
        int gap = Math.round(dp(c, 10));
        LinearLayout row = null;
        for (int i = 0; i < entries.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setBaselineAligned(false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) lp.topMargin = gap;
                container.addView(row, lp);
            }
            View tile = inflater.inflate(R.layout.item_tile, row, false);
            ((TextView) tile.findViewById(R.id.value)).setText(entries.get(i)[0]);
            ((TextView) tile.findViewById(R.id.label)).setText(entries.get(i)[1]);
            LinearLayout.LayoutParams tlp = (LinearLayout.LayoutParams) tile.getLayoutParams();
            tlp.height = ViewGroup.LayoutParams.MATCH_PARENT; // equal heights even if a label wraps
            if (i % 2 == 1) tlp.setMarginStart(gap);
            row.addView(tile);
        }
        // An odd tile out keeps half the width, matching the tiles above it.
        if (entries.size() % 2 == 1 && row != null) {
            View spacer = new View(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
            lp.setMarginStart(gap);
            row.addView(spacer, lp);
        }
    }

    public static String ms(Context c, double value) {
        if (Double.isNaN(value) || value < 0) return c.getString(R.string.value_none);
        return c.getString(R.string.value_ms, (int) Math.round(value));
    }

    public static String percent(Context c, double fraction) {
        if (Double.isNaN(fraction)) return c.getString(R.string.value_none);
        return c.getString(R.string.value_pct, (int) Math.round(fraction * 100));
    }
}

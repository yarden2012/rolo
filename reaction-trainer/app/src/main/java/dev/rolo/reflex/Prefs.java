package dev.rolo.reflex;

import android.content.Context;
import android.content.SharedPreferences;

/** User settings. */
public final class Prefs {
    public static final int LENGTH_SHORT = 0;
    public static final int LENGTH_STANDARD = 1;
    public static final int LENGTH_LONG = 2;

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public int roundLength() {
        return prefs.getInt("round_length", LENGTH_STANDARD);
    }

    public void setRoundLength(int length) {
        prefs.edit().putInt("round_length", length).apply();
    }

    public boolean haptics() {
        return prefs.getBoolean("haptics", true);
    }

    public void setHaptics(boolean on) {
        prefs.edit().putBoolean("haptics", on).apply();
    }
}

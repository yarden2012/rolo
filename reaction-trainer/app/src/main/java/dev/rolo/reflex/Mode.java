package dev.rolo.reflex;

/** The training drills. The order here is the order they appear on the home screen. */
public enum Mode {
    SIMPLE("simple", R.string.mode_simple, R.string.mode_simple_desc, R.string.mode_simple_how,
            R.drawable.ic_mode_simple, 0xFF199E70, 5, 10, 20),
    SOUND("sound", R.string.mode_sound, R.string.mode_sound_desc, R.string.mode_sound_how,
            R.drawable.ic_mode_sound, 0xFF9085E9, 5, 10, 20),
    GO_NO_GO("gonogo", R.string.mode_gonogo, R.string.mode_gonogo_desc, R.string.mode_gonogo_how,
            R.drawable.ic_mode_gonogo, 0xFFC98500, 16, 30, 60),
    CHOICE("choice", R.string.mode_choice, R.string.mode_choice_desc, R.string.mode_choice_how,
            R.drawable.ic_mode_choice, 0xFF3987E5, 10, 20, 40),
    AIM("aim", R.string.mode_aim, R.string.mode_aim_desc, R.string.mode_aim_how,
            R.drawable.ic_mode_aim, 0xFFD95926, 15, 30, 50);

    /** Stable id used in saved history; never change these. */
    public final String id;
    public final int title;
    public final int description;
    public final int howTo;
    public final int icon;
    /** The mode's identity color on charts and cards (validated as a set against the dark surface). */
    public final int color;
    private final int[] lengths;

    Mode(String id, int title, int description, int howTo, int icon, int color, int... lengths) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.howTo = howTo;
        this.icon = icon;
        this.color = color;
        this.lengths = lengths;
    }

    /** Trials in a round for the Short (0), Standard (1) or Long (2) setting. */
    public int trials(int lengthIndex) {
        return lengths[Math.max(0, Math.min(lengths.length - 1, lengthIndex))];
    }

    /** Whether rounds can contain wrong responses, so accuracy is worth reporting. */
    public boolean scoresAccuracy() {
        return this != SIMPLE && this != SOUND;
    }

    public static Mode fromId(String id) {
        for (Mode m : values()) {
            if (m.id.equals(id)) return m;
        }
        return null;
    }
}

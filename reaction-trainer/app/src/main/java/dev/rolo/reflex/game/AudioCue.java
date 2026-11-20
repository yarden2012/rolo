package dev.rolo.reflex.game;

/** An auditory stimulus whose real playback time can be read back after it plays. */
interface AudioCue {
    /** Opens the audio stream. Returns false if audio isn't available. Safe to call again. */
    boolean start();

    /** Plays the beep as soon as possible. */
    void play();

    /**
     * When the beep's first sample reached the speaker, in nanoseconds on the monotonic clock
     * (the same clock as touch events), or -1 if it hasn't been sent to the speaker yet.
     */
    long onsetNanos();

    /** Closes the stream. Safe to call again; {@link #start} may reopen it. */
    void release();
}

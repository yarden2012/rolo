package dev.rolo.reflex.game;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;
import android.util.Log;

/**
 * A beep (or your own sound, see {@link CustomCue}) on a continuously running, low-latency
 * audio stream, timed by when it actually reached the speaker.
 *
 * <p>Phones take anywhere from about 10 ms (speaker) to over 200 ms (Bluetooth) to turn
 * written audio into sound, and naive apps count that delay as your reaction time. Instead,
 * the stream plays silence the whole round (so it's warmed up and steady), the beep is written
 * at a known frame, and {@link AudioTrack#getTimestamp} maps that frame to the moment it was
 * presented at the output.
 */
final class ToneCue implements AudioCue {
    private static final String TAG = "ToneCue";
    private static final int TONE_HZ = 1000;
    private static final int TONE_MS = 120;

    static final AudioAttributes ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();

    private volatile AudioTrack track;
    private volatile boolean running;
    private volatile boolean beepPending;
    /** Frame index at which the most recent beep starts, or -1 before it's written. */
    private volatile long beepFrame = -1;
    private Thread writer;
    private int sampleRate;
    private short[] beep;
    /** Your own sound, or null for the built-in beep. */
    private final CustomCue.Sound custom;

    ToneCue(CustomCue.Sound custom) {
        this.custom = custom;
    }

    @Override
    public synchronized boolean start() {
        if (running) return true;
        sampleRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
        if (sampleRate <= 0) sampleRate = 48000;
        int minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= 0) minBytes = sampleRate / 50 * 2; // 20 ms
        AudioTrack.Builder builder = new AudioTrack.Builder()
                .setAudioAttributes(ATTRIBUTES)
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBytes * 2); // headroom against underruns
        if (Build.VERSION.SDK_INT >= 26) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        AudioTrack t;
        try {
            t = builder.build();
        } catch (RuntimeException e) {
            Log.w(TAG, "Audio output unavailable", e);
            return false;
        }
        if (t.getState() != AudioTrack.STATE_INITIALIZED) {
            t.release();
            return false;
        }
        beep = cueSamples(custom, sampleRate);
        beepFrame = -1;
        beepPending = false;
        try {
            t.play();
        } catch (IllegalStateException e) {
            t.release();
            return false;
        }
        track = t;
        running = true;
        writer = new Thread(new Runnable() {
            @Override
            public void run() {
                writeLoop();
            }
        }, "Reflex-tone");
        writer.start();
        return true;
    }

    @Override
    public void play() {
        beepFrame = -1;
        beepPending = true;
    }

    @Override
    public long onsetNanos() {
        long frame = beepFrame;
        AudioTrack t = track;
        if (frame < 0 || t == null) return -1;
        try {
            AudioTimestamp ts = new AudioTimestamp();
            if (t.getTimestamp(ts) && ts.nanoTime > 0) {
                return onsetFrom(frame, ts.framePosition, ts.nanoTime, sampleRate);
            }
            // No timestamp yet (some devices take a moment): fall back to the playback head,
            // which misses the last stretch of output latency but is close.
            long head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
            return onsetFrom(frame, head, System.nanoTime(), sampleRate);
        } catch (IllegalStateException e) {
            return -1;
        }
    }

    @Override
    public synchronized void release() {
        if (!running) return;
        running = false;
        AudioTrack t = track;
        try {
            t.pause(); // unblocks a pending write
            t.flush();
        } catch (IllegalStateException ignored) {
            // already stopped
        }
        try {
            writer.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        t.release();
        track = null;
    }

    private void writeLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        } catch (RuntimeException e) {
            // Some devices refuse; the default priority still works, just less steadily.
        }
        AudioTrack t = track;
        // Silence goes out in 2 ms chunks, so a requested beep starts within 2 ms of the
        // buffered audio ahead of it; its exact frame is recorded either way.
        short[] silence = new short[Math.max(64, sampleRate / 500)];
        long ahead = t.getBufferSizeInFrames() + 4L * silence.length;
        long written = 0;
        while (running) {
            if (beepPending) {
                beepPending = false;
                beepFrame = written;
                written += writeFully(t, beep);
            } else {
                written += writeFully(t, silence);
            }
            // Writes normally block once the buffer is full. If a device (or a test double)
            // doesn't, don't race ahead of what's actually being played.
            while (running && written - (t.getPlaybackHeadPosition() & 0xFFFFFFFFL) > ahead) {
                pause2ms();
            }
        }
    }

    private int writeFully(AudioTrack t, short[] data) {
        int off = 0;
        while (running && off < data.length) {
            int n = t.write(data, off, data.length - off);
            if (n <= 0) {
                pause2ms();
                break;
            }
            off += n;
        }
        return off;
    }

    /** A real 2 ms nap (not SystemClock.sleep, which test clocks fake). */
    private static void pause2ms() {
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The cue to play at {@code sampleRate}: your sound if there is one, else the beep. */
    static short[] cueSamples(CustomCue.Sound custom, int sampleRate) {
        if (custom == null || custom.samples.length == 0) return synthesize(sampleRate);
        return CustomCue.resample(custom.samples, custom.rate, sampleRate);
    }

    /** The time a frame was (or will be) presented, from a reference frame with a known time. */
    static long onsetFrom(long frame, long refFrame, long refNanos, int sampleRate) {
        return refNanos + (frame - refFrame) * 1_000_000_000L / sampleRate;
    }

    /**
     * A 120 ms beep: 1 kHz with a touch of its octave, a 2 ms attack (sharp, but no click)
     * and a 30 ms fade, peaking at 80% of full scale.
     */
    static short[] synthesize(int sampleRate) {
        int n = sampleRate * TONE_MS / 1000;
        int attack = Math.max(1, sampleRate * 2 / 1000);
        int fade = Math.max(1, sampleRate * 30 / 1000);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double env = i < attack ? (double) i / attack : i > n - fade ? (double) (n - i) / fade : 1;
            double t = (double) i / sampleRate;
            double s = 0.75 * Math.sin(2 * Math.PI * TONE_HZ * t) + 0.25 * Math.sin(4 * Math.PI * TONE_HZ * t);
            out[i] = (short) Math.round(env * 0.8 * s * Short.MAX_VALUE);
        }
        return out;
    }

    /** Whether media is currently muted, so the beep would be silent. */
    static boolean volumeOff(Context context) {
        AudioManager am = context.getSystemService(AudioManager.class);
        return am != null && am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0;
    }

    /** Whether the beep goes to Bluetooth, whose extra delay isn't always reported. */
    static boolean bluetoothOutput(Context context) {
        AudioManager am = context.getSystemService(AudioManager.class);
        if (am == null) return false;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = d.getType();
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true;
            if (Build.VERSION.SDK_INT >= 31
                    && (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)) {
                return true;
            }
        }
        return false;
    }
}

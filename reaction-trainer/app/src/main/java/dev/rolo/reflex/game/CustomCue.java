package dev.rolo.reflex.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

/**
 * Your own sound for Sound reaction. A file you pick is decoded once, mixed to mono, trimmed so
 * it starts exactly where it becomes audible (so the timing starts with the sound itself, not
 * with silence at the front of the file), capped at 1.5 s, evened out in loudness and kept in
 * the app's private storage. The original file is never touched or copied.
 */
public final class CustomCue {
    private static final String TAG = "CustomCue";
    private static final String FILE = "cue.pcm";
    private static final String KEY_NAME = "cue_name";
    private static final int MAGIC = 0x52464C58; // "RFLX"
    static final int MAX_MS = 1500;
    private static final int MAX_DECODE_SECONDS = 20;

    /** Mono 16-bit audio. */
    public static final class Sound {
        public final int rate;
        public final short[] samples;

        public Sound(int rate, short[] samples) {
            this.rate = rate;
            this.samples = samples;
        }
    }

    private static AudioTrack previewTrack;

    private CustomCue() {}

    /** The chosen file's name, or null while Sound reaction uses the built-in beep. */
    public static String name(Context context) {
        return prefs(context).getString(KEY_NAME, null);
    }

    /** The stored sound, or null for the built-in beep (or if it can't be read). */
    public static Sound load(Context context) {
        if (name(context) == null) return null;
        try {
            ByteBuffer b = ByteBuffer.wrap(file(context).readFully()).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt() != MAGIC) return null;
            int rate = b.getInt();
            short[] s = new short[b.getInt()];
            b.asShortBuffer().get(s);
            return new Sound(rate, s);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not read the custom sound", e);
            return null;
        }
    }

    /** Back to the built-in beep. */
    public static void clear(Context context) {
        prefs(context).edit().remove(KEY_NAME).apply();
        file(context).delete();
    }

    /** Decodes, prepares and stores the audio at {@code uri}. Slow: call it off the main thread. */
    public static void importFrom(Context context, Uri uri, String name) throws IOException {
        save(context, prepare(decode(context, uri)), name);
    }

    static void save(Context context, Sound sound, String name) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(12 + 2 * sound.samples.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(MAGIC).putInt(sound.rate).putInt(sound.samples.length);
        b.asShortBuffer().put(sound.samples);
        AtomicFile f = file(context);
        FileOutputStream out = f.startWrite();
        try {
            out.write(b.array());
            f.finishWrite(out);
        } catch (IOException e) {
            f.failWrite(out);
            throw e;
        }
        prefs(context).edit().putString(KEY_NAME, name).apply();
    }

    // ---------------------------------------------------------------- preview

    /** Plays the Sound reaction cue (yours, or the beep) once. */
    public static synchronized void preview(Context context) {
        stopPreview();
        int rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
        if (rate <= 0) rate = 48000;
        short[] s = ToneCue.cueSamples(load(context), rate);
        try {
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(ToneCue.ATTRIBUTES)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(Math.max(2, 2 * s.length))
                    .build();
            t.write(s, 0, s.length);
            t.play();
            previewTrack = t;
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not preview the sound", e);
        }
    }

    public static synchronized void stopPreview() {
        if (previewTrack == null) return;
        try {
            previewTrack.stop();
        } catch (IllegalStateException ignored) {
            // never started
        }
        previewTrack.release();
        previewTrack = null;
    }

    // ---------------------------------------------------------------- decoding

    /** Decodes the first audio track of any format the phone supports into mono PCM. */
    static Sound decode(Context context, Uri uri) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(context, uri, null);
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) throw new IOException("No audio track");
            extractor.selectTrack(track);
            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int encoding = AudioFormat.ENCODING_PCM_16BIT;

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            short[] pcm = new short[1 << 16];
            int count = 0;
            boolean inputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int idle = 0;
            while (true) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(in);
                        int size = buf == null ? -1 : extractor.readSampleData(buf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 10_000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = codec.getOutputFormat();
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING);
                } else if (out >= 0) {
                    idle = 0;
                    ByteBuffer buf = codec.getOutputBuffer(out);
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        buf = buf.slice().order(ByteOrder.nativeOrder());
                        int n = encoding == AudioFormat.ENCODING_PCM_FLOAT ? info.size / 4 : info.size / 2;
                        if (count + n > pcm.length) pcm = Arrays.copyOf(pcm, Math.max(pcm.length * 2, count + n));
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            FloatBuffer fb = buf.asFloatBuffer();
                            for (int i = 0; i < n; i++) {
                                float v = Math.max(-1f, Math.min(1f, fb.get(i)));
                                pcm[count + i] = (short) Math.round(v * Short.MAX_VALUE);
                            }
                        } else {
                            ShortBuffer sb = buf.asShortBuffer();
                            sb.get(pcm, count, n);
                        }
                        count += n;
                    }
                    codec.releaseOutputBuffer(out, false);
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    boolean longEnough = count >= (long) rate * Math.max(1, channels) * MAX_DECODE_SECONDS;
                    if (eos || longEnough) break;
                } else if (++idle > 300) { // ~3 s without output: give up
                    throw new IOException("Decoder stalled");
                }
            }
            if (count == 0) throw new IOException("No audio decoded");
            return new Sound(rate, downmix(Arrays.copyOf(pcm, count), Math.max(1, channels)));
        } catch (RuntimeException e) {
            throw new IOException("Could not decode audio", e);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                    // not started
                }
                codec.release();
            }
            extractor.release();
        }
    }

    // ---------------------------------------------------------------- processing

    /** Trims the silent lead-in, caps the length and evens out the level. */
    static Sound prepare(Sound raw) throws IOException {
        short[] s = trimLeadingSilence(raw.samples, raw.rate);
        if (s.length == 0) throw new IOException("The sound is silent");
        int max = raw.rate * MAX_MS / 1000;
        if (s.length > max) {
            s = Arrays.copyOf(s, max);
            fadeOut(s, raw.rate * 20 / 1000);
        }
        normalize(s, 0.9, 8.0);
        return new Sound(raw.rate, s);
    }

    /** Averages interleaved channels into one. */
    static short[] downmix(short[] interleaved, int channels) {
        if (channels <= 1) return interleaved;
        short[] out = new short[interleaved.length / channels];
        for (int i = 0; i < out.length; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) sum += interleaved[i * channels + c];
            out[i] = (short) (sum / channels);
        }
        return out;
    }

    /**
     * Drops everything before the sound becomes audible (its first sample above 5% of the peak),
     * keeping 1 ms before that so the attack is intact.
     */
    static short[] trimLeadingSilence(short[] s, int rate) {
        int peak = 0;
        for (short v : s) peak = Math.max(peak, Math.abs(v));
        if (peak == 0) return new short[0];
        int threshold = Math.max(1, peak / 20);
        int first = 0;
        while (first < s.length && Math.abs(s[first]) < threshold) first++;
        int start = Math.max(0, first - rate / 1000);
        return Arrays.copyOfRange(s, start, s.length);
    }

    /** Ramps the last {@code n} samples down to silence. */
    static void fadeOut(short[] s, int n) {
        n = Math.min(n, s.length);
        for (int i = 0; i < n; i++) {
            int idx = s.length - n + i;
            s[idx] = (short) Math.round(s[idx] * (double) (n - 1 - i) / n);
        }
    }

    /** Scales so the peak sits at {@code targetPeak} of full scale, boosting by at most {@code maxGain}. */
    static void normalize(short[] s, double targetPeak, double maxGain) {
        int peak = 0;
        for (short v : s) peak = Math.max(peak, Math.abs(v));
        if (peak == 0) return;
        double gain = Math.min(maxGain, targetPeak * Short.MAX_VALUE / peak);
        for (int i = 0; i < s.length; i++) {
            long v = Math.round(s[i] * gain);
            s[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
        }
    }

    /** Linear-interpolation resampling; plenty for a short cue. */
    static short[] resample(short[] in, int fromRate, int toRate) {
        if (fromRate == toRate || in.length == 0) return in.clone();
        int n = (int) Math.max(1, Math.round((long) in.length * (double) toRate / fromRate));
        short[] out = new short[n];
        double step = n == 1 ? 0 : (double) (in.length - 1) / (n - 1);
        for (int i = 0; i < n; i++) {
            double pos = i * step;
            int a = (int) pos;
            int b = Math.min(in.length - 1, a + 1);
            double f = pos - a;
            out[i] = (short) Math.round(in[a] * (1 - f) + in[b] * f);
        }
        return out;
    }

    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), FILE));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }
}

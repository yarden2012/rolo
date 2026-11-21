package dev.rolo.reflex.game;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CustomCueTest {

    @Test
    public void downmixAveragesChannels() {
        short[] stereo = {100, 300, -200, -400, 10, 10};
        assertArrayEquals(new short[] {200, -300, 10}, CustomCue.downmix(stereo, 2));
        short[] mono = {1, 2, 3};
        assertArrayEquals(mono, CustomCue.downmix(mono, 1));
    }

    @Test
    public void trimKeepsOneMillisecondBeforeTheSoundBecomesAudible() {
        // 1000 samples of silence, a little hiss well under 5% of the peak, then the hit.
        int rate = 48_000;
        short[] s = new short[3000];
        for (int i = 1000; i < 1010; i++) s[i] = (short) (i % 2 == 0 ? 20 : -20);
        s[1010] = 10_000;
        for (int i = 1011; i < s.length; i++) s[i] = 4000;
        short[] trimmed = CustomCue.trimLeadingSilence(s, rate);
        assertEquals(s.length - (1010 - rate / 1000), trimmed.length);
        assertEquals("the onset sits 1 ms in", 10_000, trimmed[rate / 1000]);
    }

    @Test
    public void silentFilesAreRejected() {
        try {
            CustomCue.prepare(new CustomCue.Sound(48_000, new short[48_000]));
            fail("a silent sound was accepted");
        } catch (IOException expected) {
            // good
        }
    }

    @Test
    public void prepareCapsTheLengthFadesOutAndEvensTheLevel() throws IOException {
        int rate = 48_000;
        short[] sine = new short[rate * 3]; // 3 s, far longer than the cap
        for (int i = 0; i < sine.length; i++) sine[i] = (short) Math.round(10_000 * Math.sin(2 * Math.PI * 440 * i / rate));
        CustomCue.Sound out = CustomCue.prepare(new CustomCue.Sound(rate, sine));
        // The sine is audible from its first samples, so nothing is trimmed: exactly 1.5 s is kept.
        assertEquals(rate * CustomCue.MAX_MS / 1000, out.samples.length);
        int peak = 0;
        for (short v : out.samples) peak = Math.max(peak, Math.abs(v));
        assertTrue("normalised to ~90%: " + peak, Math.abs(peak - 0.9 * Short.MAX_VALUE) < 400);
        assertTrue("fades to silence", Math.abs(out.samples[out.samples.length - 1]) < 100);
    }

    @Test
    public void quietSoundsAreBoostedButNotWithoutLimit() {
        short[] s = {0, 100, -100, 50};
        CustomCue.normalize(s, 0.9, 8.0);
        assertEquals(800, s[1]); // 8x cap, not the 295x it would take to reach 90%
    }

    @Test
    public void resampleKeepsDurationAndEnds() {
        short[] ramp = new short[480];
        for (int i = 0; i < ramp.length; i++) ramp[i] = (short) (i * 10);
        short[] out = CustomCue.resample(ramp, 48_000, 44_100);
        assertEquals(441, out.length);
        assertEquals(ramp[0], out[0]);
        assertEquals(ramp[ramp.length - 1], out[out.length - 1]);
        assertArrayEquals(ramp, CustomCue.resample(ramp, 48_000, 48_000));
    }

    @Test
    public void storesLoadsAndResetsTheSound() throws IOException {
        Context context = RuntimeEnvironment.getApplication();
        assertNull("starts on the built-in beep", CustomCue.name(context));
        assertNull(CustomCue.load(context));

        short[] samples = {0, 1200, -3400, 32_000, -32_000, 7};
        CustomCue.save(context, new CustomCue.Sound(44_100, samples), "crit1.ogg");
        assertEquals("crit1.ogg", CustomCue.name(context));
        CustomCue.Sound loaded = CustomCue.load(context);
        assertNotNull(loaded);
        assertEquals(44_100, loaded.rate);
        assertArrayEquals(samples, loaded.samples);

        CustomCue.clear(context);
        assertNull(CustomCue.name(context));
        assertNull(CustomCue.load(context));
    }

    @Test
    public void soundReactionPlaysYourSoundWhenThereIsOne() {
        short[] beep = ToneCue.cueSamples(null, 48_000);
        assertEquals(ToneCue.synthesize(48_000).length, beep.length);

        short[] mine = new short[1000];
        for (int i = 0; i < mine.length; i++) mine[i] = (short) (i % 100 * 300);
        short[] played = ToneCue.cueSamples(new CustomCue.Sound(24_000, mine), 48_000);
        assertEquals("resampled to the output rate", 2000, played.length);
        assertEquals(mine[0], played[0]);
    }
}

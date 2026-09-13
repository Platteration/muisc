package dev.muisc.dsp.stretch

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Synth
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PitchShifterTest {
    private val sr = 44100

    @Test
    fun octaveUpDoublesFrequencyAndKeepsLength() {
        val x = Synth.sine(sr, 440.0, 2.0, amp = 0.5f)
        val y = PitchShifter().shift(AudioBuffer.mono(sr, x), 12.0)
        assertEquals(x.size, y.frames)
        val f = SigMeasure.peakFrequency(y[0], sr, 32768, offset = 10000, minHz = 100.0, maxHz = 4000.0)
        assertEquals(880.0, f, 880.0 * 0.005, "peak $f Hz")
        val fz = SigMeasure.zeroCrossingFrequency(y[0], sr, 10000, 22050)
        assertEquals(880.0, fz, 880.0 * 0.01, "zero-crossing estimate $fz Hz")
        // Level within 1 dB (sinc resampling is flat in the pass-band).
        val lvl = SigMeasure.db(SigMeasure.rms(y[0], 5000, y.frames - 5000) / SigMeasure.rms(x))
        assertTrue(abs(lvl) < 1.0, "level $lvl dB")
    }

    @Test
    fun minusOneSemitoneWithinOnePercent() {
        val x = Synth.sine(sr, 440.0, 2.0, amp = 0.5f)
        val y = PitchShifter().shift(AudioBuffer.mono(sr, x), -1.0)
        assertEquals(x.size, y.frames)
        val expected = 440.0 * PitchShifter.semitonesToRatio(-1.0) // 415.30 Hz
        val f = SigMeasure.peakFrequency(y[0], sr, 32768, offset = 10000, minHz = 100.0, maxHz = 2000.0)
        assertEquals(expected, f, expected * 0.01, "peak $f Hz")
    }

    @Test
    fun zeroSemitonesIsACopyAndStereoIsSupported() {
        val l = Synth.sine(sr, 300.0, 0.5)
        val r = Synth.whiteNoise(sr, 0.5, seed = 3)
        val buf = AudioBuffer.stereo(sr, l, r)
        val same = PitchShifter().shift(buf, 0.0)
        assertEquals(buf.frames, same.frames)
        for (i in 0 until buf.frames) { assertEquals(l[i], same[0][i], 0f); assertEquals(r[i], same[1][i], 0f) }
        val up = PitchShifter().shift(buf, 7.0)
        assertEquals(2, up.channelCount)
        assertEquals(buf.frames, up.frames)
        val f = SigMeasure.peakFrequency(up[0], sr, 16384, offset = 2000, minHz = 100.0, maxHz = 2000.0)
        assertEquals(300.0 * PitchShifter.semitonesToRatio(7.0), f, 4.5, "fifth up: $f Hz")
    }
}

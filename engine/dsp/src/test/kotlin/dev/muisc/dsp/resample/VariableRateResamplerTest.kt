package dev.muisc.dsp.resample

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Synth
import dev.muisc.dsp.filter.Measure
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VariableRateResamplerTest {
    private val fs = 44100

    @Test
    fun ratioOneIsIdentity() {
        val x = Synth.whiteNoise(fs, 0.2, seed = 4)
        val r = VariableRateResampler()
        val y = FloatArray(x.size)
        val produced = r.process(x, y, x.size)
        assertEquals(x.size, produced)
        for (i in x.indices) assertEquals(x[i], y[i], 1e-4f, "sample $i")
        assertEquals(x.size.toDouble(), r.positionInInput, 1e-9)
        // past the end: zeros, and the count reports it
        val more = r.process(x, y, 10)
        assertEquals(0, more)
        for (i in 0 until 10) assertEquals(0f, y[i], 0f)
        assertTrue(r.isPastEnd(x.size))
    }

    @Test
    fun ratioTwoDoublesFrequency() {
        val x = Synth.sine(fs, 1000.0, 1.0)
        val r = VariableRateResampler()
        r.setRatio(2.0)
        val n = fs / 2 - 100
        val y = FloatArray(n)
        assertEquals(n, r.process(x, y, n))
        val at2k = Measure.amplitude(y, fs, 2000.0, 100, 16384)
        val at1k = Measure.amplitude(y, fs, 1000.0, 100, 16384)
        assertEquals(0.5, at2k, 0.5 * 0.01)
        assertTrue(at1k < 0.005, "1 kHz leftover $at1k")
        // zero crossings: 2 per period at 2 kHz over 0.25 s -> ~1000
        var zc = 0
        for (i in 1 until n / 2) if ((y[i] >= 0f) != (y[i - 1] >= 0f)) zc++
        assertTrue(abs(zc - 2 * 2000 * (n / 2) / fs.toDouble()) <= 2, "zero crossings $zc")
        assertEquals(2.0 * n, r.positionInInput, 1e-9)
    }

    @Test
    fun rampToZeroStopsAdvancing() {
        val x = Synth.sine(fs, 200.0, 1.0)
        val r = VariableRateResampler()
        val n = 1000
        val y = FloatArray(n)
        r.setRatioRamp(1.0, 0.0)
        r.process(x, y, n)
        // sum_{i<N} (1 - i/N) = N - (N-1)/2
        assertEquals(n - (n - 1) / 2.0, r.positionInInput, 1e-6)
        assertEquals(0.0, r.ratio, 0.0)
        val before = r.positionInInput
        r.process(x, y, n)
        assertEquals(before, r.positionInInput, 0.0)
        val frozen = VariableRateResampler.hermite(x, x.size, before)
        for (i in 0 until n) assertEquals(frozen, y[i], 0f)
        // ramping back up resumes
        r.setRatioRamp(0.0, 1.0)
        r.process(x, y, n)
        assertEquals(before + (n - 1) / 2.0, r.positionInInput, 1e-6)
        assertEquals(1.0, r.ratio, 0.0)
    }

    @Test
    fun smallTempoChangeKeepsSinePure() {
        val x = Synth.sine(fs, 1000.0, 1.0)
        val r = VariableRateResampler()
        r.setRatio(1.01)
        val n = 40000
        val y = FloatArray(n)
        r.process(x, y, n)
        val w = 2 * PI * 1010.0 / fs
        val err = FloatArray(n - 10) { y[it] - (0.5 * sin(w * it)).toFloat() }
        val rel = Measure.db(Measure.rms(err) / (0.5 / Math.sqrt(2.0)))
        assertTrue(rel < -50.0, "deviation from ideal 1010 Hz sine: $rel dB")
        assertEquals(0.5, Measure.amplitude(y, fs, 1010.0, 0, 32768), 0.5 * 0.002)
    }

    @Test
    fun antiAliasOptionSuppressesFoldedHighFrequencies() {
        val x = Synth.sine(fs, 15000.0, 1.0)
        val n = 20000
        val plain = FloatArray(n); val aa = FloatArray(n)
        VariableRateResampler(antiAlias = false).also { it.setRatio(2.0) }.process(x, plain, n)
        VariableRateResampler(antiAlias = true).also { it.setRatio(2.0) }.process(x, aa, n)
        // 15 kHz at 2x = 30 kHz -> folds to 14.1 kHz without filtering
        val foldPlain = Measure.amplitude(plain, fs, 14100.0, 100, 16384)
        val foldAa = Measure.amplitude(aa, fs, 14100.0, 100, 16384)
        assertTrue(foldPlain > 0.05, "plain interpolation aliases: $foldPlain")
        assertTrue(Measure.db(foldAa / foldPlain) < -12.0, "anti-alias suppression ${Measure.db(foldAa / foldPlain)} dB")
        // and a low tone at 2x survives with the anti-alias filter
        val low = Synth.sine(fs, 1000.0, 1.0)
        val yl = FloatArray(n)
        VariableRateResampler(antiAlias = true).also { it.setRatio(2.0) }.process(low, yl, n)
        assertEquals(0.0, Measure.db(Measure.amplitude(yl, fs, 2000.0, 100, 16384) / 0.5), 0.5)
    }

    @Test
    fun stereoChannelsShareThePosition() {
        val buf = AudioBuffer.stereo(fs, Synth.sine(fs, 300.0, 0.5), Synth.sine(fs, 700.0, 0.5))
        val r = VariableRateResampler(channels = 2)
        r.setRatio(0.5)
        val out = Array(2) { FloatArray(1000) }
        r.process(buf, out, 1000)
        assertEquals(500.0, r.positionInInput, 1e-9)
        // ratio 0.5 halves frequency: 300 -> 150 Hz on the left, 700 -> 350 Hz on the right
        val mono = VariableRateResampler(); mono.setRatio(0.5)
        val ref = FloatArray(1000); mono.process(buf[1], ref, 1000)
        for (i in 0 until 1000) assertEquals(ref[i], out[1][i], 0f)
        assertEquals(0.5, Measure.amplitude(out[0], fs, 150.0, 0, 882), 0.02)
    }
}

package dev.muisc.dsp.synth

import dev.muisc.dsp.stretch.SigMeasure
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OscillatorTest {
    private val sr = 44100

    @Test
    fun polyBlepSawAliasingIsBelowMinus40dB() {
        val osc = Oscillator(sr, Waveform.SAW)
        osc.setFrequency(1000.0)
        val n = 65536
        val x = FloatArray(n + 4096)
        osc.process(x, x.size)
        // Exact-frequency Hann lock-in (no bin scalloping): harmonics and aliases have known frequencies.
        fun amp(sig: FloatArray, hz: Double) = SigMeasure.amplitudeAt(sig, sr, hz, 2048, n)
        val fund = amp(x, 1000.0)
        assertEquals(2.0 / Math.PI, fund, 0.02, "fundamental amplitude 2/pi")
        // Harmonic amplitudes follow 1/k times the B-spline kernel's sinc^4(f/fs) roll-off.
        for (k in intArrayOf(2, 3, 5, 10, 15)) {
            val f = 1000.0 * k
            val s = Math.sin(Math.PI * f / sr) / (Math.PI * f / sr)
            val expected = -20.0 * Math.log10(k.toDouble()) + 40.0 * Math.log10(s * s)
            assertEquals(expected, SigMeasure.db(amp(x, f) / fund), 0.5, "harmonic $k")
        }
        // Aliases of harmonics 23..33 fold into 11.1..21.1 kHz (above Nyquist/2).
        for (k in 23..33) {
            val alias = sr - 1000.0 * k
            val rel = SigMeasure.db(amp(x, alias) / fund)
            assertTrue(rel < -40.0, "alias of harmonic $k at $alias Hz is $rel dB")
        }
        // Naive sawtooth for reference: its aliases are far louder (-21..-24 dB).
        val naive = FloatArray(n + 4096) { val t = (it * 1000.0 / sr) % 1.0; (2 * t - 1).toFloat() }
        val worstNaive = (23..33).maxOf { SigMeasure.db(amp(naive, sr - 1000.0 * it) / amp(naive, 1000.0)) }
        assertTrue(worstNaive > -30.0, "naive saw alias level $worstNaive dB")
    }

    @Test
    fun polyBlepSquareHasOddHarmonicsOnly() {
        val osc = Oscillator(sr, Waveform.SQUARE)
        osc.setFrequency(500.0)
        val n = 65536
        val x = FloatArray(n)
        osc.process(x, n)
        val mag = SigMeasure.spectrum(x, n)
        val fund = SigMeasure.peakNear(mag, 500.0, sr, n)
        assertEquals(-9.54, SigMeasure.db(SigMeasure.peakNear(mag, 1500.0, sr, n) / fund), 0.5, "3rd harmonic")
        assertEquals(-13.98, SigMeasure.db(SigMeasure.peakNear(mag, 2500.0, sr, n) / fund), 0.5, "5th harmonic")
        assertTrue(SigMeasure.db(SigMeasure.peakNear(mag, 1000.0, sr, n) / fund) < -40.0, "2nd harmonic")
        assertTrue(SigMeasure.db(SigMeasure.peakNear(mag, 2000.0, sr, n) / fund) < -40.0, "4th harmonic")
        // Time-domain: values stay within [-1.1, 1.1] (small BLEP overshoot only).
        for (v in x) assertTrue(abs(v) < 1.15f)
    }

    @Test
    fun sineFrequencyAndExponentialGlide() {
        val osc = Oscillator(sr, Waveform.SINE)
        osc.setFrequency(440.0)
        val x = FloatArray(sr)
        osc.process(x, sr)
        assertEquals(440.0, SigMeasure.peakFrequency(x, sr, 32768, 0, 100.0, 1000.0), 1.0)
        assertEquals(1f, x.max(), 1e-3f)
        osc.setGlide(200.0, 800.0, sr, exponential = true)
        assertTrue(osc.isGliding)
        val y = FloatArray(sr)
        osc.process(y, sr)
        assertTrue(!osc.isGliding)
        assertEquals(800.0, osc.frequencyHz, 1e-9)
        val win = (0.04 * sr).toInt()
        val fStart = SigMeasure.zeroCrossingFrequency(y, sr, 0, win)
        val fMid = SigMeasure.zeroCrossingFrequency(y, sr, sr / 2 - win / 2, win)
        val fEnd = SigMeasure.zeroCrossingFrequency(y, sr, sr - win, win)
        assertEquals(200.0, fStart, 12.0, "start")
        assertEquals(400.0, fMid, 15.0, "middle (geometric mean)")
        assertEquals(800.0, fEnd, 25.0, "end")
        val lin = Oscillator(sr, Waveform.SINE)
        lin.setGlide(200.0, 800.0, sr, exponential = false)
        val z = FloatArray(sr)
        lin.process(z, sr)
        assertEquals(500.0, SigMeasure.zeroCrossingFrequency(z, sr, sr / 2 - win / 2, win), 15.0, "linear glide middle")
        // The BLEP residual is continuous and integrates the step: r(-2) = r(2) = 0, r(0-) - r(0+) = 1.
        assertEquals(0.0, Oscillator.blepResidual(-2.0), 1e-12)
        assertEquals(0.0, Oscillator.blepResidual(2.0), 1e-12)
        assertEquals(1.0 / 24, Oscillator.blepResidual(-1.0), 1e-12)
        assertEquals(-1.0 / 24, Oscillator.blepResidual(1.0), 1e-12)
        assertEquals(1.0, Oscillator.blepResidual(-1e-9) - Oscillator.blepResidual(1e-9), 1e-6)
    }

    @Test
    fun adsrShape() {
        val env = Adsr(sr, attackSec = 0.1, decaySec = 0.1, sustain = 0.5, releaseSec = 0.2)
        val e = env.render(sr, sr / 2)
        assertEquals(sr, e.size)
        assertEquals(0f, e[0], 0f)
        val a = (0.1 * sr).toInt()
        assertEquals(1f, e[a], 1e-3f, "attack peak")
        for (i in 1..a) assertTrue(e[i] >= e[i - 1], "attack monotone at $i")
        assertEquals(0.75f, e[a + a / 2], 1e-3f, "mid decay")
        assertEquals(0.5f, e[2 * a], 1e-3f, "sustain reached")
        assertEquals(0.5f, e[sr / 2 - 1], 1e-3f, "sustain held")
        val r = (0.2 * sr).toInt()
        assertEquals(0.25f, e[sr / 2 + r / 2], 1e-3f, "mid release")
        assertEquals(0f, e[sr / 2 + r + 1], 1e-6f, "released")
        assertEquals(0f, e[sr - 1], 0f)
        assertTrue(!env.isActive)
        // Instant attack/release edge cases.
        val fast = Adsr(sr, 0.0, 0.0, 0.8, 0.0)
        fast.gateOn()
        assertEquals(0.8f, fast.nextSample(), 1e-6f)
        fast.gateOff()
        assertEquals(0f, fast.nextSample(), 0f)
    }
}

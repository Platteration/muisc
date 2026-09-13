package dev.muisc.dsp.filter

import dev.muisc.audio.synth.Synth
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StateVariableFilterTest {
    private val fs = 44100

    private fun measuredGainDb(svf: StateVariableFilter, freq: Double): Double {
        svf.reset()
        val x = Synth.sine(fs, freq, 1.0)
        val y = FloatArray(x.size)
        svf.process(x, y)
        val start = fs / 2; val len = 16384
        return Measure.db(Measure.amplitude(y, fs, freq, start, len) / Measure.amplitude(x, fs, freq, start, len))
    }

    @Test
    fun lowPassPassesLowAndAttenuatesHighAsAnalyticResponsePredicts() {
        val svf = StateVariableFilter(fs, 1, cutoffHz = 1000.0)
        svf.mode = SvfMode.LOW_PASS
        assertEquals(0.0, measuredGainDb(svf, 100.0), 0.1)
        assertEquals(-3.0103, measuredGainDb(svf, 1000.0), 0.15)
        val g10k = measuredGainDb(svf, 10000.0)
        assertTrue(g10k < -35.0, "10 kHz gain $g10k")
        for (f in doubleArrayOf(300.0, 1000.0, 3000.0, 10000.0)) assertEquals(svf.magnitudeDb(f), measuredGainDb(svf, f), 0.2, "at $f")
    }

    @Test
    fun highPassBandPassAndNotchOutputs() {
        val svf = StateVariableFilter(fs, 1, cutoffHz = 1000.0, q = 2.0)
        svf.mode = SvfMode.HIGH_PASS
        assertEquals(0.0, measuredGainDb(svf, 10000.0), 0.1)
        assertTrue(measuredGainDb(svf, 100.0) < -35.0)
        svf.mode = SvfMode.BAND_PASS
        assertEquals(0.0, measuredGainDb(svf, 1000.0), 0.05)
        assertTrue(measuredGainDb(svf, 100.0) < -20.0)
        assertTrue(measuredGainDb(svf, 10000.0) < -20.0)
        svf.mode = SvfMode.NOTCH
        assertTrue(measuredGainDb(svf, 1000.0) < -40.0)
        assertEquals(0.0, measuredGainDb(svf, 100.0), 0.2)
    }

    @Test
    fun stableAtHighResonanceWhileSweeping() {
        val svf = StateVariableFilter(fs, 2, cutoffHz = 200.0, q = 20.0)
        svf.mode = SvfMode.LOW_PASS
        val n = fs * 2
        val input = arrayOf(Synth.whiteNoise(fs, 2.0, seed = 21), Synth.whiteNoise(fs, 2.0, seed = 22))
        val out = Array(2) { FloatArray(n) }
        svf.setCutoffRamp(100.0, 18000.0, n, exponential = true)
        val block = 512
        var pos = 0
        while (pos < n) {
            val len = minOf(block, n - pos)
            val inBlk = Array(2) { input[it].copyOfRange(pos, pos + len) }
            val outBlk = Array(2) { FloatArray(len) }
            svf.process(inBlk, outBlk, len)
            for (ch in 0..1) System.arraycopy(outBlk[ch], 0, out[ch], pos, len)
            pos += len
        }
        var peak = 0f
        for (ch in 0..1) for (v in out[ch]) { assertFalse(v.isNaN() || v.isInfinite()); if (abs(v) > peak) peak = abs(v) }
        // Q = 20 is +26 dB at resonance; white noise of amplitude 0.5 cannot blow up beyond a few units.
        assertTrue(peak < 20f, "peak $peak")
        assertTrue(peak > 0.1f)
        assertFalse(svf.isRamping)
        assertEquals(18000.0, svf.cutoffHz, 1e-6)
    }

    @Test
    fun cutoffRampReachesTargetAndFollowsShape() {
        val svf = StateVariableFilter(fs, 1)
        val silence = FloatArray(3000)
        val y = FloatArray(3000)
        svf.setCutoffRamp(200.0, 5000.0, 3000, exponential = true)
        svf.process(silence, y, 1000)
        assertEquals(200.0 * 25.0.pow(1.0 / 3.0), svf.cutoffHz, 1e-6)
        svf.process(silence, y, 1000); svf.process(silence, y, 1000)
        assertEquals(5000.0, svf.cutoffHz, 1e-9)
        assertFalse(svf.isRamping)
        svf.process(silence, y, 1000)
        assertEquals(5000.0, svf.cutoffHz, 1e-9)

        svf.setCutoffRamp(200.0, 5000.0, 3000, exponential = false)
        svf.process(silence, y, 1000)
        assertEquals(1800.0, svf.cutoffHz, 1e-6)
        // a ramp that ends mid-block lands exactly on the target
        svf.process(silence, y, 2500)
        assertEquals(5000.0, svf.cutoffHz, 1e-9)

        svf.setCutoff(440.0); svf.setResonance(3.0)
        svf.process(silence, y, 1000)
        assertEquals(440.0, svf.cutoffHz, 1e-9)
        assertEquals(3.0, svf.resonance, 1e-9)
    }

    @Test
    fun perBlockSmoothingHasNoClickOnDc() {
        val svf = StateVariableFilter(fs, 1, cutoffHz = 100.0)
        val dc = FloatArray(4096) { 1f }
        val y = FloatArray(4096)
        svf.process(dc, y); svf.process(dc, y)
        svf.setCutoff(8000.0)
        svf.process(dc, y)
        for (i in y.indices) assertTrue(abs(y[i] - 1f) < 0.02f, "sample $i = ${y[i]}")
    }
}

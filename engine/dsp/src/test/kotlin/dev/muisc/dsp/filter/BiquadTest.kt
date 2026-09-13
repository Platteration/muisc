package dev.muisc.dsp.filter

import dev.muisc.audio.synth.Synth
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BiquadTest {
    private val sr = 44100.0

    @Test
    fun lowPassIsMinus3dBAtCutoffAndRollsOff40dBPerDecade() {
        val c = Biquad.lowPass(200.0, sr)
        assertEquals(-3.0103, c.magnitudeDb(200.0, sr), 0.02)
        assertEquals(0.0, c.magnitudeDb(20.0, sr), 0.05)
        assertEquals(-40.0, c.magnitudeDb(2000.0, sr), 1.0)
        assertTrue(c.isStable)
    }

    @Test
    fun highPassIsMinus3dBAtCutoffAndRollsOff() {
        val c = Biquad.highPass(2000.0, sr)
        assertEquals(-3.0103, c.magnitudeDb(2000.0, sr), 0.02)
        assertEquals(0.0, c.magnitudeDb(15000.0, sr), 0.05)
        assertEquals(-40.0, c.magnitudeDb(200.0, sr), 1.0)
    }

    @Test
    fun peakingEqHasGainAtCentreAndUnityFarAway() {
        val c = Biquad.peakingEq(1000.0, sr, 1.0, 6.0)
        assertEquals(6.0, c.magnitudeDb(1000.0, sr), 0.01)
        assertEquals(0.0, c.magnitudeDb(20.0, sr), 0.05)
        assertEquals(0.0, c.magnitudeDb(20000.0, sr), 0.05)
        val cut = Biquad.peakingEq(1000.0, sr, 2.0, -12.0)
        assertEquals(-12.0, cut.magnitudeDb(1000.0, sr), 0.01)
    }

    @Test
    fun shelvesReachTheirGainsAndHalfGainAtCorner() {
        val low = Biquad.lowShelf(300.0, sr, 6.0)
        assertEquals(6.0, low.magnitudeDb(1.0, sr), 0.05)
        assertEquals(3.0, low.magnitudeDb(300.0, sr), 0.1)
        assertEquals(0.0, low.magnitudeDb(15000.0, sr), 0.05)
        val high = Biquad.highShelf(3000.0, sr, -9.0)
        assertEquals(-9.0, high.magnitudeDb(22000.0, sr), 0.05)
        assertEquals(-4.5, high.magnitudeDb(3000.0, sr), 0.1)
        assertEquals(0.0, high.magnitudeDb(30.0, sr), 0.05)
    }

    @Test
    fun allPassNotchAndBandPassMagnitudes() {
        val ap = Biquad.allPass(1000.0, sr, 2.0)
        for (k in 1..30) assertEquals(0.0, ap.magnitudeDb(20.0 * Math.pow(1000.0, k / 30.0), sr), 1e-6)
        val notch = Biquad.notch(1000.0, sr, 4.0)
        assertTrue(notch.magnitudeDb(1000.0, sr) < -80.0)
        assertEquals(0.0, notch.magnitudeDb(100.0, sr), 0.1)
        val bp = Biquad.bandPass(1000.0, sr, 4.0)
        assertEquals(0.0, bp.magnitudeDb(1000.0, sr), 1e-6)
        assertTrue(bp.magnitudeDb(100.0, sr) < -20.0)
        val bpSkirt = Biquad.bandPass(1000.0, sr, 4.0, constantSkirt = true)
        assertEquals(20 * log10(4.0), bpSkirt.magnitudeDb(1000.0, sr), 1e-6)
    }

    @Test
    fun processingASineMatchesAnalyticAttenuationWithin0p2dB() {
        val fs = sr.toInt()
        val c = Biquad.lowPass(1000.0, sr)
        val f = BiquadFilter(1)
        f.setCoefficients(c, immediate = true)
        for (freq in doubleArrayOf(500.0, 1000.0, 2000.0, 4000.0)) {
            f.reset()
            val x = Synth.sine(fs, freq, 1.0)
            val y = FloatArray(x.size)
            f.process(x, y)
            val start = fs / 2
            val len = 16384
            val gainDb = Measure.db(Measure.amplitude(y, fs, freq, start, len) / Measure.amplitude(x, fs, freq, start, len))
            assertEquals(c.magnitudeDb(freq, sr), gainDb, 0.2, "freq=$freq")
        }
    }

    @Test
    fun coefficientRampReachesTargetWithoutDiscontinuity() {
        val fs = sr.toInt()
        val f = BiquadFilter(1)
        val target = Biquad.lowPass(300.0, sr)
        val x = FloatArray(4096) { 1f } // DC step
        val y = FloatArray(4096)
        f.process(x, y) // identity, settles at 1
        f.setCoefficients(target) // ramped over the next block
        f.process(x, y)
        assertEquals(target, f.coefficients)
        // DC gain of the LPF is 1, so the output must stay near 1 all along the ramp (no zipper transient).
        for (i in y.indices) assertTrue(abs(y[i] - 1f) < 0.05f, "sample $i = ${y[i]}")
        val mid = Biquad.interpolate(BiquadCoefficients.IDENTITY, target, 0.5)
        assertEquals((1.0 + target.b0) / 2, mid.b0, 1e-12)
        assertEquals(target.a2 / 2, mid.a2, 1e-12)
    }

    @Test
    fun cascadeEqualsSequentialSectionsAndSumsMagnitudes() {
        val fs = sr.toInt()
        val c = Biquad.lowPass(800.0, sr)
        val cascade = BiquadCascade(2, listOf(c, c))
        assertEquals(2 * c.magnitudeDb(800.0, sr), cascade.magnitudeDb(800.0, sr), 1e-9)
        val n = 2000
        val input = arrayOf(Synth.whiteNoise(fs, n / fs.toDouble(), seed = 3), Synth.whiteNoise(fs, n / fs.toDouble(), seed = 4))
        val out = Array(2) { FloatArray(n) }
        cascade.process(input, out, n)
        for (ch in 0..1) {
            val a = BiquadFilter(1, c); val b = BiquadFilter(1, c)
            val ref = FloatArray(n)
            for (i in 0 until n) ref[i] = b.processSample(a.processSample(input[ch][i]))
            for (i in 0 until n) assertEquals(ref[i], out[ch][i], 1e-6f)
        }
    }

    @Test
    fun responseMatchesMagnitude() {
        val c = Biquad.peakingEq(2000.0, sr, 0.7, 4.0)
        for (f in doubleArrayOf(50.0, 2000.0, 9000.0)) {
            val r = c.response(f, sr)
            assertEquals(c.magnitudeDb(f, sr), 20 * log10(sqrt(r[0] * r[0] + r[1] * r[1])), 1e-9)
        }
    }
}

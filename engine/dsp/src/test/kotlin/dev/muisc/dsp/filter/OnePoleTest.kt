package dev.muisc.dsp.filter

import dev.muisc.audio.synth.Synth
import kotlin.math.E
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnePoleTest {
    private val fs = 44100

    @Test
    fun stepResponseReaches63PercentAfterOneTimeConstant() {
        val lp = OnePole()
        lp.setTimeConstant(0.01, fs.toDouble())
        val tauFrames = (0.01 * fs).toInt()
        var y = 0f
        for (i in 0 until tauFrames) y = lp.processSample(1f)
        assertEquals(1.0 - 1.0 / E, y.toDouble(), 0.002)
        for (i in 0 until 10 * tauFrames) y = lp.processSample(1f)
        assertEquals(1.0, y.toDouble(), 1e-4)
        assertEquals(y, lp.value(), 1e-7f)
    }

    @Test
    fun lowPassSineAttenuationMatchesAnalyticResponse() {
        val lp = OnePole()
        lp.setCutoff(100.0, fs.toDouble())
        assertEquals(-3.0103, lp.magnitudeDb(100.0, fs.toDouble()), 0.05)
        for (f in doubleArrayOf(20.0, 100.0, 1000.0, 5000.0)) {
            lp.reset()
            val x = Synth.sine(fs, f, 1.0)
            val y = FloatArray(x.size)
            lp.process(x, y)
            val start = fs / 2; val len = 16384
            val g = Measure.db(Measure.amplitude(y, fs, f, start, len) / Measure.amplitude(x, fs, f, start, len))
            assertEquals(lp.magnitudeDb(f, fs.toDouble()), g, 0.1, "at $f Hz")
        }
        assertTrue(lp.magnitudeDb(10000.0, fs.toDouble()) < -35.0)
    }

    @Test
    fun highPassBlocksDcAndComplementsLowPass() {
        val hp = OnePole(mode = OnePoleMode.HIGH_PASS)
        val lp = OnePole(mode = OnePoleMode.LOW_PASS)
        hp.setCutoff(50.0, fs.toDouble()); lp.setCutoff(50.0, fs.toDouble())
        val x = Synth.whiteNoise(fs, 0.5, seed = 8)
        for (i in x.indices) x[i] += 0.3f // add DC offset
        val h = FloatArray(x.size); val l = FloatArray(x.size)
        hp.process(x, h); lp.process(x, l)
        for (i in x.indices) assertEquals(x[i], h[i] + l[i], 1e-6f)
        // DC is removed: mean of the HP output over the settled half is ~0
        var mean = 0.0
        for (i in x.size / 2 until x.size) mean += h[i]
        mean /= x.size / 2
        assertTrue(abs(mean) < 0.005, "mean $mean")
        assertTrue(hp.magnitudeDb(0.0, fs.toDouble()) < -100.0)
        assertEquals(0.0, hp.magnitudeDb(10000.0, fs.toDouble()), 0.05)
    }

    @Test
    fun channelsAreIndependent() {
        val f = OnePole(channels = 2)
        f.setCoefficient(0.1)
        val input = arrayOf(FloatArray(100) { 1f }, FloatArray(100) { -1f })
        val out = Array(2) { FloatArray(100) }
        f.process(input, out, 100)
        for (i in 0 until 100) assertEquals(out[0][i], -out[1][i], 1e-7f)
        assertEquals(0.1f, out[0][0], 1e-7f)
        assertEquals(0.19f, out[0][1], 1e-6f)
    }
}

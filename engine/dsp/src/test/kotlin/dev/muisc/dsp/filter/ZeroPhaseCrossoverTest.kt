package dev.muisc.dsp.filter

import dev.muisc.audio.AudioBuffer
import dev.muisc.audio.synth.Synth
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZeroPhaseCrossoverTest {
    private val fs = 44100
    private val edges = doubleArrayOf(200.0, 4000.0)

    private fun split(x: FloatArray, freqs: DoubleArray = edges): Array<FloatArray> {
        val out = Array(freqs.size + 1) { FloatArray(x.size) }
        ZeroPhaseCrossover.split(x, x.size, fs, freqs, out)
        return out
    }

    @Test
    fun bandsSumToTheInputSampleExactly() {
        // Noise with a hard start and a hard end: the edges are where a filtfilt's transients live.
        var state = 99L
        val x = FloatArray(30000) {
            state = state * 6364136223846793005L + 1442695040888963407L
            ((state ushr 40).toInt() % 2001 - 1000) / 1500f
        }
        val bands = split(x)
        var worst = 0.0
        for (i in x.indices) worst = maxOf(worst, abs((bands[0][i] + bands[1][i] + bands[2][i] - x[i]).toDouble()))
        assertTrue(worst < 1e-6, "sum differs from the input by $worst")
        // The all-pass-compensated causal crossover does not have this property (its sum is AP(f1) AP(f2) x).
        val causal = MultibandCrossover(fs, 1, edges).split(AudioBuffer.mono(fs, x))
        var causalWorst = 0.0
        for (i in x.indices) causalWorst = maxOf(causalWorst, abs((causal[0][0][i] + causal[1][0][i] + causal[2][0][i] - x[i]).toDouble()))
        assertTrue(causalWorst > 0.1, "control: the causal sum is not the input ($causalWorst)")
    }

    @Test
    fun bandsHaveTheLr4MagnitudesOfTheCausalCrossover() {
        val win = 16384
        val causal = MultibandCrossover(fs, 1, edges)
        for (f in Measure.binFrequencies(14, 30.0, 16000.0, fs, win)) {
            val x = Synth.sine(fs, f, 1.0)
            val zp = split(x)
            val cs = causal.split(AudioBuffer.mono(fs, x))
            val start = (x.size - win) / 2
            for (b in 0 until 3) {
                val zDb = Measure.db(Measure.amplitude(zp[b], fs, f, start, win) / 0.5 + 1e-12)
                val cDb = Measure.db(Measure.amplitude(cs[b][0], fs, f, start, win) / 0.5 + 1e-12)
                if (cDb > -60.0) assertEquals(cDb, zDb, 0.1, "band $b at ${"%.1f".format(f)} Hz")
            }
        }
    }

    @Test
    fun anImpulseStaysWhereItIs() {
        val n = 8192
        val at = 4000
        val x = FloatArray(n).also { it[at] = 1f }
        val bands = split(x)
        for ((b, band) in bands.withIndex()) {
            var peak = 0
            for (i in band.indices) if (abs(band[i]) > abs(band[peak])) peak = i
            assertEquals(at, peak, "band $b peaks on the impulse")
            // Zero phase: the impulse response is symmetric about the impulse.
            for (k in 1 until 2000) assertEquals(band[at - k], band[at + k], 1e-6f, "band $b symmetric at ±$k")
        }
        // The causal low band peaks well after the impulse (its group delay), which is what the zero-phase split removes.
        val causal = MultibandCrossover(fs, 1, edges).split(AudioBuffer.mono(fs, x))[0][0]
        var cPeak = 0
        for (i in causal.indices) if (abs(causal[i]) > abs(causal[cPeak])) cPeak = i
        assertTrue(cPeak - at > 40, "control: causal low band peaks ${cPeak - at} frames late")
    }

    @Test
    fun shortBuffersAndBadArgumentsAreHandled() {
        for (n in 0..3) {
            val x = FloatArray(n) { (it + 1) * 0.1f }
            val bands = split(x)
            for (i in 0 until n) assertEquals(x[i], bands[0][i] + bands[1][i] + bands[2][i], 1e-6f)
        }
        assertFailsWith<IllegalArgumentException> { split(FloatArray(10), doubleArrayOf(4000.0, 200.0)) }
        assertFailsWith<IllegalArgumentException> { split(FloatArray(10), doubleArrayOf()) }
        assertFailsWith<IllegalArgumentException> { ZeroPhaseCrossover.split(FloatArray(10), 10, fs, edges, Array(2) { FloatArray(10) }) }
    }
}

package dev.muisc.dsp.fx

import dev.muisc.audio.synth.Synth
import dev.muisc.dsp.stretch.SigMeasure
import dev.muisc.dsp.texture.ThirdOctaveBands
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FdnReverbTest {
    private val sr = 44100

    /** RT60 from the Schroeder backward integral of the impulse response, fitted between -5 and -25 dB. */
    private fun schroederRt60(h: FloatArray): Double {
        val edc = DoubleArray(h.size)
        var acc = 0.0
        for (i in h.indices.reversed()) { acc += h[i].toDouble() * h[i]; edc[i] = acc }
        val total = edc[0]
        var t5 = -1; var t25 = -1
        for (i in h.indices) {
            val db = 10.0 * log10(edc[i] / total)
            if (t5 < 0 && db <= -5.0) t5 = i
            if (t25 < 0 && db <= -25.0) { t25 = i; break }
        }
        require(t5 > 0 && t25 > t5) { "decay range not reached" }
        return 60.0 / 20.0 * (t25 - t5) / sr
    }

    @Test
    fun rt60MatchesTargetWithin20Percent() {
        for (target in doubleArrayOf(0.8, 1.5, 3.0)) {
            val rv = FdnReverb(sr, 1)
            rv.decaySeconds = target
            rv.dampingHz = 22050.0 // bypass damping: uniform decay in every band
            rv.mix = 1.0
            val n = (target * 2.5 * sr).toInt()
            val x = FloatArray(n); x[0] = 1f
            val y = FloatArray(n)
            rv.process(arrayOf(x), arrayOf(y), n)
            val rt = schroederRt60(y)
            assertEquals(target, rt, target * 0.2, "RT60 for target $target s: $rt s")
        }
    }

    @Test
    fun freezeHoldsTheTailEnergy() {
        val rv = FdnReverb(sr, 2)
        rv.decaySeconds = 2.0
        rv.mix = 1.0
        val excite = Synth.whiteNoise(sr, 0.5, amp = 0.5f, seed = 5)
        val out = Array(2) { FloatArray(excite.size) }
        rv.process(arrayOf(excite), out, excite.size)
        rv.freeze = true
        val n = 5 * sr
        val zeros = Array(1) { FloatArray(4096) }
        val tail = Array(2) { FloatArray(n) }
        var done = 0
        while (done < n) { val m = minOf(4096, n - done); rv.process(zeros, tail, m, 0, done); done += m }
        val start = SigMeasure.rms(tail[0], 0, sr / 2)
        val end = SigMeasure.rms(tail[0], n - sr / 2, n)
        val diff = SigMeasure.db(end / start)
        assertTrue(kotlin.math.abs(diff) < 3.0, "frozen tail level changed by $diff dB (start $start, end $end)")
        assertTrue(start > 0.01, "tail present")
        // Un-freezing lets it decay again.
        rv.freeze = false
        val after = Array(2) { FloatArray(3 * sr) }
        done = 0
        while (done < 3 * sr) { val m = minOf(4096, 3 * sr - done); rv.process(zeros, after, m, 0, done); done += m }
        val decayed = SigMeasure.rms(after[0], 3 * sr - sr / 2, 3 * sr)
        assertTrue(SigMeasure.db(decayed / end) < -40.0, "decay after unfreeze ${SigMeasure.db(decayed / end)} dB")
    }

    @Test
    fun stableForThirtySecondsOfNoise() {
        val rv = FdnReverb(sr, 2)
        rv.decaySeconds = 8.0
        rv.dampingHz = 4000.0
        rv.mix = 0.5
        val block = 4096
        val inp = Array(2) { FloatArray(block) }
        val out = Array(2) { FloatArray(block) }
        val rnd = kotlin.random.Random(3)
        var peak = 0f
        var done = 0
        val total = 30 * sr
        while (done < total) {
            val n = minOf(block, total - done)
            for (c in 0 until 2) for (i in 0 until n) inp[c][i] = rnd.nextFloat() - 0.5f
            rv.process(inp, out, n)
            for (c in 0 until 2) for (i in 0 until n) {
                val v = out[c][i]
                assertTrue(!v.isNaN() && !v.isInfinite(), "non-finite output at ${done + i}")
                if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
            }
            done += n
        }
        assertTrue(peak < 5f, "peak $peak")
        assertTrue(peak > 0.1f, "reverb produced output")
    }

    @Test
    fun dampingShortensHighFrequencyDecayAndLinesArePrime() {
        val rv = FdnReverb(sr, 1)
        rv.decaySeconds = 2.0
        rv.dampingHz = 1500.0
        rv.mix = 1.0
        val n = 2 * sr
        val x = FloatArray(n); x[0] = 1f
        val y = FloatArray(n)
        rv.process(arrayOf(x), arrayOf(y), n)
        val early = ThirdOctaveBands.measureDb(y.copyOfRange(0, sr / 4), sr, 4096)
        val late = ThirdOctaveBands.measureDb(y.copyOfRange(sr, sr + sr / 4), sr, 4096)
        val lowDrop = late[ThirdOctaveBands.bandOf(200.0)] - early[ThirdOctaveBands.bandOf(200.0)]
        val highDrop = late[ThirdOctaveBands.bandOf(8000.0)] - early[ThirdOctaveBands.bandOf(8000.0)]
        assertTrue(highDrop < lowDrop - 10.0, "high band dropped $highDrop dB, low band $lowDrop dB")
        val lengths = rv.delayLengths
        assertEquals(8, lengths.size)
        for (i in lengths.indices) {
            assertTrue(FdnReverb.isPrime(lengths[i]), "length ${lengths[i]} prime")
            if (i > 0) assertTrue(lengths[i] > lengths[i - 1], "lengths increasing")
        }
        assertTrue(lengths[0] > 0.02 * sr && lengths[7] < 0.075 * sr, "lengths in 23..68 ms: ${lengths.toList()}")
        // Hadamard is orthogonal: norm preserved with the 1/sqrt(N) scale.
        val v = FloatArray(8) { (it + 1).toFloat() }
        val before = v.sumOf { it.toDouble() * it }
        FdnReverb.hadamard(v, 8)
        val after = v.sumOf { it.toDouble() * it } / 8.0
        assertEquals(before, after, 1e-6)
    }
}

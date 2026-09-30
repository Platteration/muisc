package dev.muisc.dsp.filter

import kotlin.math.ceil
import kotlin.math.min

/**
 * Offline N-band crossover with **zero phase** whose bands sum back to the input exactly.
 *
 * Band 0 is the input run forward and then backward through the Butterworth section of the LR4 low-pass at
 * `f1` ([LinkwitzRiley.lr4LowPassSection], Gustafsson's "filtfilt"): the two passes square the magnitude — exactly
 * the LR4 low-pass magnitude, -6 dB at the corner and 24 dB/oct — and cancel the phase. The remainder `x - band0`
 * is then the zero-phase LR4 high-pass magnitude, because the Butterworth low- and high-pass are power
 * complementary (`|LP|² + |HP|² = 1`, which the bilinear transform preserves). The remainder is split the same way
 * at `f2`, and so on; the last band is what is left. So:
 *  - the bands have the same magnitudes as [MultibandCrossover]'s;
 *  - there is no phase rotation and no group delay: a band's transients sit where the input's do;
 *  - the bands sum to the input sample-exactly (up to float rounding), so a deck whose band gains are all equal
 *    comes out as the deck itself rather than as an all-pass version of it.
 *
 * [MultibandCrossover]'s sum is `AP(f1)…AP(fN) x`: at 200 Hz that delays a kick by ~2.5 ms and turns its attack
 * into a ramp. That is unavoidable in a real-time EQ (the player's live fallback keeps its causal crossover), but a
 * pre-rendered transition sits between dry guard regions and must not move the beat.
 *
 * The price is whole-buffer processing and a symmetric (non-causal) impulse response: a band starts to rise a few
 * milliseconds before the transient that excites it (at 200 Hz the low band's impulse response is above 10 % of
 * its peak for 2.1 ms either side of it). To keep the filters' start-up transients out of the buffer, each channel
 * is extended at both ends by an odd reflection of [padFrames] frames (as `scipy.signal.filtfilt` does), filtered,
 * and trimmed again. Whatever edge transient remains is still exactly complementary between the bands, so it
 * cancels wherever the band gains agree.
 */
object ZeroPhaseCrossover {
    /** Default padding: six periods of the lowest corner frequency (30 ms at 200 Hz), at most the buffer length - 1. */
    fun padFrames(sampleRate: Int, lowestHz: Double, frames: Int): Int =
        min(frames - 1, ceil(6.0 * sampleRate / lowestHz).toInt()).coerceAtLeast(0)

    /**
     * Splits the first [frames] samples of [input] into `out[band]` (`crossoverFreqs.size + 1` arrays of at least
     * [frames] samples; they may not alias [input]). Frequencies must be ascending and inside `(0, sampleRate / 2)`.
     */
    fun split(input: FloatArray, frames: Int, sampleRate: Int, crossoverFreqs: DoubleArray, out: Array<FloatArray>) {
        require(crossoverFreqs.isNotEmpty()) { "at least one crossover frequency" }
        require(crossoverFreqs.all { it > 0 && it < sampleRate / 2.0 }) { "frequencies must lie in (0, fs/2)" }
        for (i in 1 until crossoverFreqs.size) require(crossoverFreqs[i] > crossoverFreqs[i - 1]) { "frequencies must be ascending" }
        require(out.size == crossoverFreqs.size + 1) { "expected ${crossoverFreqs.size + 1} band outputs" }
        require(frames in 0..input.size && out.all { it.size >= frames }) { "buffers shorter than $frames frames" }
        if (frames == 0) return
        val pad = padFrames(sampleRate, crossoverFreqs[0], frames)
        val len = frames + 2 * pad
        // rem = the not-yet-split remainder, padded; lp = scratch for one band, padded.
        val rem = DoubleArray(len)
        val x0 = input[0].toDouble(); val xn = input[frames - 1].toDouble()
        for (i in 0 until pad) {
            rem[pad - 1 - i] = 2.0 * x0 - input[i + 1].toDouble()
            rem[pad + frames + i] = 2.0 * xn - input[frames - 2 - i].toDouble()
        }
        for (i in 0 until frames) rem[pad + i] = input[i].toDouble()
        val lp = DoubleArray(len)
        for ((k, f) in crossoverFreqs.withIndex()) {
            filtfilt(LinkwitzRiley.lr4LowPassSection(f, sampleRate.toDouble()), rem, lp)
            val band = out[k]
            for (i in 0 until frames) band[i] = lp[pad + i].toFloat()
            for (i in 0 until len) rem[i] -= lp[i]
        }
        val last = out[crossoverFreqs.size]
        for (i in 0 until frames) last[i] = rem[pad + i].toFloat()
    }

    /** Forward-backward biquad in double precision: `y = reverse(H(reverse(H(x))))`. */
    private fun filtfilt(c: BiquadCoefficients, x: DoubleArray, y: DoubleArray) {
        val n = x.size
        var s1 = 0.0; var s2 = 0.0
        for (i in 0 until n) {
            val v = x[i]
            val o = c.b0 * v + s1
            s1 = c.b1 * v - c.a1 * o + s2
            s2 = c.b2 * v - c.a2 * o
            y[i] = o
        }
        s1 = 0.0; s2 = 0.0
        for (i in n - 1 downTo 0) {
            val v = y[i]
            val o = c.b0 * v + s1
            s1 = c.b1 * v - c.a1 * o + s2
            s2 = c.b2 * v - c.a2 * o
            y[i] = o
        }
    }
}

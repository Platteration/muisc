package dev.muisc.dsp.fx

import dev.muisc.audio.AudioBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * DJ loop roll / beat repeat: plays a sequence of loops that all start at the same source frame ([render]),
 * e.g. 1 bar, 1/2, 1/4, 1/8, 1/16 bar, producing the classic accelerating stutter before a drop.
 *
 * Each loop `i` occupies exactly `loopLengths[i]` output frames and contains `source[loopStart, loopStart +
 * loopLengths[i])`, so the output length is exactly the sum of the loop lengths and every loop starts on its
 * grid position. Loop boundaries are joined with a short equal-power crossfade ([crossfadeMs], 2–5 ms) centred
 * on the boundary: the outgoing loop keeps playing its natural continuation in the source for half the
 * crossfade and the incoming loop starts half a crossfade early (reading the source just before `loopStart`),
 * so there is no discontinuity and no timing shift. The start of the first loop and the end of the last are
 * left untouched (they line up with whatever the strategy plays before and after).
 */
class LoopRoll(val crossfadeMs: Double = 3.0) {
    init { require(crossfadeMs >= 0.0) { "crossfadeMs must be >= 0" } }

    /** Renders the roll; `loopLengths` must all be positive. Source positions outside the buffer read as zero. */
    fun render(source: AudioBuffer, loopStart: Int, loopLengths: IntArray): AudioBuffer {
        require(loopLengths.all { it > 0 }) { "loop lengths must be positive" }
        val ch = source.channelCount
        val sr = source.sampleRate
        var total = 0L
        for (l in loopLengths) total += l
        require(total <= Int.MAX_VALUE) { "roll too long" }
        val out = Array(ch) { FloatArray(total.toInt()) }
        var o = 0
        for (len in loopLengths) {
            for (c in 0 until ch) copyRange(source[c], loopStart, len, out[c], o)
            o += len
        }
        val xf = Math.round(crossfadeMs * sr / 1000.0).toInt()
        o = 0
        for (i in 0 until loopLengths.size - 1) {
            val lenOut = loopLengths[i]
            o += lenOut
            val x = minOf(xf, lenOut, loopLengths[i + 1])
            if (x < 2) continue
            val half = x / 2
            for (j in -half until x - half) {
                val theta = (j + half + 0.5) / x * (PI / 2)
                val gOut = cos(theta).toFloat()
                val gIn = sin(theta).toFloat()
                val pos = o + j
                for (c in 0 until ch) {
                    val s = source[c]
                    val a = sampleAt(s, loopStart + lenOut + j)
                    val b = sampleAt(s, loopStart + j)
                    out[c][pos] = a * gOut + b * gIn
                }
            }
        }
        return AudioBuffer(sr, out)
    }

    private fun sampleAt(x: FloatArray, i: Int): Float = if (i >= 0 && i < x.size) x[i] else 0f

    private fun copyRange(src: FloatArray, from: Int, len: Int, dst: FloatArray, dstOff: Int) {
        val c0 = maxOf(from, 0)
        val c1 = minOf(from + len, src.size)
        if (c1 > c0) System.arraycopy(src, c0, dst, dstOff + (c0 - from), c1 - c0)
    }

    companion object {
        /**
         * Builds the classic halving sequence from a beat length: `bar, bar/2, bar/4, ...` for [steps] steps
         * (bar = `beatFrames * beatsPerBar`). With [fillBar] every division is repeated `2^k` times so that each
         * step lasts one full bar (1 bar, 2 x 1/2, 4 x 1/4, ...); otherwise each length is played once.
         */
        fun sequenceFromBeat(beatFrames: Double, beatsPerBar: Int = 4, steps: Int = 5, fillBar: Boolean = false): IntArray {
            require(beatFrames > 0 && beatsPerBar > 0 && steps > 0) { "beatFrames, beatsPerBar and steps must be positive" }
            val bar = beatFrames * beatsPerBar
            val list = ArrayList<Int>()
            for (k in 0 until steps) {
                val div = 1 shl k
                val len = Math.round(bar / div).toInt().coerceAtLeast(1)
                val reps = if (fillBar) div else 1
                repeat(reps) { list += len }
            }
            return list.toIntArray()
        }

        /** Loop lengths for arbitrary beat fractions, e.g. `[1.0, 0.5, 0.25, 0.125]` beats. */
        fun lengthsFromBeatFractions(beatFrames: Double, beatFractions: DoubleArray): IntArray =
            IntArray(beatFractions.size) { Math.round(beatFrames * beatFractions[it]).toInt().coerceAtLeast(1) }
    }
}

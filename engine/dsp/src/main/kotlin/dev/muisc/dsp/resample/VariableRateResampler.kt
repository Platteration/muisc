package dev.muisc.dsp.resample

import dev.muisc.audio.AudioBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * "Vinyl-style" variable-rate resampler: reads an input signal at a fractional position that advances by
 * [ratio] input frames per output frame, using 4-point cubic Hermite (Catmull-Rom) interpolation:
 *
 *     c0 = x1,  c1 = (x2 - x0) / 2,  c2 = x0 - 2.5 x1 + 2 x2 - x3 / 2,  c3 = (x3 - x0) / 2 + 1.5 (x1 - x2)
 *     y  = ((c3 f + c2) f + c1) f + c0        (f = fractional part of the position)
 *
 * The ratio can be set per block ([setRatio]) or ramped linearly across the next block ([setRatioRamp]), so
 * it covers small tempo nudges (< 2 %), pitch shifting, and brake stops (ratio -> 0: the position stops
 * advancing and the output freezes on the current sample). ratio = 1 is bit-exact identity, ratio = 2 doubles
 * pitch/speed. Negative ratios play backwards.
 *
 * With [antiAlias] enabled and ratio > 1 the Catmull-Rom kernel `K(t)` (support (-2, 2), partition of unity)
 * is stretched by the ratio, `y = (1 / r) sum_k x[k] K((p - k) / r)`, which turns the interpolator into a
 * short low-pass at `fs / r` and removes most of the aliasing that plain 4-point interpolation would fold back;
 * the cost grows linearly with the ratio. It is a light-weight pre-filter, not a brick wall (about 15-20 dB
 * of suppression), which is adequate for the small speed-ups used in beat-matching.
 *
 * The reader has no latency: output frame i reads input position `positionInInput` at the time of the call,
 * then advances. Positions outside the input read as zero (short-circuited), so playback runs past the end
 * with silence; [process] returns how many frames were produced before the end of the input was passed.
 * Allocation-free; the input is any planar array or [AudioBuffer].
 */
class VariableRateResampler(val channels: Int = 1, val antiAlias: Boolean = false) {
    init { require(channels > 0) }

    /** Current read position in input frames (fractional). */
    var positionInInput: Double = 0.0

    /** Ratio in effect at the start of the next block (input frames advanced per output frame). */
    var ratio: Double = 1.0
        private set
    private var targetRatio = 1.0
    private var ramping = false

    /** Sets the playback ratio for the next block (constant across the block). */
    fun setRatio(r: Double) { ratio = r; targetRatio = r; ramping = false }

    /**
     * Ramps the ratio linearly from [from] to [to] across the next processed block: output frame i of a block
     * of N frames advances by `from + (to - from) * i / N`; after the block the ratio is exactly [to].
     */
    fun setRatioRamp(from: Double, to: Double) { ratio = from; targetRatio = to; ramping = true }

    fun reset(position: Double = 0.0) { positionInInput = position; ramping = false; ratio = targetRatio }

    /** Reads [frames] output frames from [input] into `output[ch][offset..]`. Returns frames produced before passing the input end. */
    fun process(input: Array<FloatArray>, output: Array<FloatArray>, frames: Int, outputOffset: Int = 0): Int {
        require(input.size >= channels && output.size >= channels)
        val inLen = input[0].size
        val r0 = ratio
        val dr = if (ramping) (targetRatio - r0) / frames else 0.0
        val start = positionInInput
        var valid = 0
        for (ch in 0 until channels) {
            val x = input[ch]; val y = output[ch]
            var p = start
            var r = r0
            var v = 0
            val useAA = antiAlias
            for (i in 0 until frames) {
                y[outputOffset + i] = if (useAA && abs(r) > 1.0) stretched(x, inLen, p, abs(r)) else hermite(x, inLen, p)
                if (p < inLen) v = i + 1
                p += r
                r += dr
            }
            if (ch == 0) { positionInInput = p; valid = v }
        }
        if (ramping) { ratio = targetRatio; ramping = false }
        return valid
    }

    /** Mono convenience. */
    fun process(input: FloatArray, output: FloatArray, frames: Int, outputOffset: Int = 0): Int =
        process(arrayOf(input), arrayOf(output), frames, outputOffset)

    /** Reads from an [AudioBuffer] (must have at least [channels] channels). */
    fun process(input: AudioBuffer, output: Array<FloatArray>, frames: Int, outputOffset: Int = 0): Int =
        process(input.channels, output, frames, outputOffset)

    /** True when the read position has run past the end of an input of [inputFrames] frames. */
    fun isPastEnd(inputFrames: Int): Boolean = positionInInput >= inputFrames

    companion object {
        /** Catmull-Rom interpolation of [x] at fractional position [p]; out-of-range samples read as zero. */
        fun hermite(x: FloatArray, len: Int, p: Double): Float {
            val fl = floor(p)
            val i = fl.toInt()
            if (i < -2 || i > len) return 0f
            val f = (p - fl).toFloat()
            val x0 = if (i - 1 in 0 until len) x[i - 1] else 0f
            val x1 = if (i in 0 until len) x[i] else 0f
            val x2 = if (i + 1 in 0 until len) x[i + 1] else 0f
            val x3 = if (i + 2 in 0 until len) x[i + 2] else 0f
            val c1 = 0.5f * (x2 - x0)
            val c2 = x0 - 2.5f * x1 + 2f * x2 - 0.5f * x3
            val c3 = 0.5f * (x3 - x0) + 1.5f * (x1 - x2)
            return ((c3 * f + c2) * f + c1) * f + x1
        }

        /** Catmull-Rom kernel K(t), support (-2, 2). */
        fun kernel(t: Double): Double {
            val a = abs(t)
            return when {
                a < 1.0 -> (1.5 * a - 2.5) * a * a + 1.0
                a < 2.0 -> ((-0.5 * a + 2.5) * a - 4.0) * a + 2.0
                else -> 0.0
            }
        }

        /** Kernel stretched by [r] (>= 1): `(1/r) sum_k x[k] K((p - k) / r)`. */
        fun stretched(x: FloatArray, len: Int, p: Double, r: Double): Float {
            val span = 2.0 * r
            var k0 = ceil(p - span).toInt()
            var k1 = floor(p + span).toInt()
            if (k0 < 0) k0 = 0
            if (k1 >= len) k1 = len - 1
            if (k1 < k0) return 0f
            var acc = 0.0
            val inv = 1.0 / r
            for (k in k0..k1) acc += x[k] * kernel((p - k) * inv)
            return (acc * inv).toFloat()
        }
    }
}

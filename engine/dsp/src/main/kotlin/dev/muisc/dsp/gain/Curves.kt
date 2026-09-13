package dev.muisc.dsp.gain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * Fade / crossfade shapes. Every shape is a monotonic function `f: [0, 1] -> [0, 1]` with `f(0) = 0`,
 * `f(1) = 1` (the fade-in gain); the matching fade-out gain is `f(1 - x)` — see [Curves.fadeIn] / [Curves.fadeOut].
 */
enum class FadeShape {
    /** `x` — constant-voltage crossfade (correlated sources sum to unity). */
    LINEAR,
    /** `sin(pi/2 x)` / `cos(pi/2 x)` — constant-power crossfade (uncorrelated sources keep loudness). */
    EQUAL_POWER,
    /** Smoothstep `3x^2 - 2x^3` — gentle start and end. */
    S_CURVE,
    /** `(e^{kx} - 1) / (e^k - 1)`, k = 4 — slow start, fast finish (perceptually "late" fade-in). */
    EXPONENTIAL,
    /** Mirror of EXPONENTIAL — fast start, slow finish (perceptually "early" fade-in). */
    LOGARITHMIC,
    /** `sqrt(x)` — the p-law with p = 0.5 (constant power, straight lines in the power domain). */
    SQUARE_ROOT,
}

/**
 * Crossfade laws, gain ramps and dB helpers used by every transition strategy.
 *
 * Laws are pure functions of `x in [0, 1]`; the ramp/apply helpers fill or scale FloatArrays without allocating
 * (beyond the returned array where documented). All functions are deterministic and total for in-range input;
 * out-of-range x is clamped to [0, 1].
 */
object Curves {
    const val EXP_CURVATURE: Double = 4.0

    private fun clamp01(x: Double): Double = if (x < 0.0) 0.0 else if (x > 1.0) 1.0 else x

    /** Linear law `x`. */
    fun linear(x: Double): Double = clamp01(x)

    /** Equal-power fade-in gain `sin(pi/2 x)`; with [equalPowerOut] the squares sum to exactly 1. */
    fun equalPowerIn(x: Double): Double = sin(PI / 2 * clamp01(x))

    /** Equal-power fade-out gain `cos(pi/2 x)`. */
    fun equalPowerOut(x: Double): Double = cos(PI / 2 * clamp01(x))

    /**
     * p-law fade-in `x^p`. p = 1 is linear (constant voltage: gains sum to 1), p = 0.5 is constant power
     * (squares sum to 1), values in between trade a small centre dip for less centre bump.
     */
    fun pLaw(x: Double, p: Double): Double = clamp01(x).pow(p)

    /** Smoothstep `3x^2 - 2x^3`. */
    fun sCurve(x: Double): Double { val t = clamp01(x); return t * t * (3.0 - 2.0 * t) }

    /** Exponential fade `(e^{kx} - 1) / (e^k - 1)`: slow start, fast end. */
    fun exponential(x: Double, k: Double = EXP_CURVATURE): Double = (exp(k * clamp01(x)) - 1.0) / (exp(k) - 1.0)

    /** Logarithmic fade: mirror image of [exponential] (`1 - exponential(1 - x)`), fast start, slow end. */
    fun logarithmic(x: Double, k: Double = EXP_CURVATURE): Double = 1.0 - exponential(1.0 - clamp01(x), k)

    /**
     * Fade that is linear in decibels from [floorDb] (at x = 0, returned as exactly 0) to 0 dB at x = 1; the
     * "fader law" of a mixing console.
     */
    fun dbLinear(x: Double, floorDb: Double = -60.0): Double {
        val t = clamp01(x)
        return if (t <= 0.0) 0.0 else dbToLinear(floorDb * (1.0 - t))
    }

    /** Fade-in gain of [shape] at [x]. */
    fun fadeIn(shape: FadeShape, x: Double): Double = when (shape) {
        FadeShape.LINEAR -> linear(x)
        FadeShape.EQUAL_POWER -> equalPowerIn(x)
        FadeShape.S_CURVE -> sCurve(x)
        FadeShape.EXPONENTIAL -> exponential(x)
        FadeShape.LOGARITHMIC -> logarithmic(x)
        FadeShape.SQUARE_ROOT -> pLaw(x, 0.5)
    }

    /** Fade-out gain of [shape] at [x], defined as `fadeIn(shape, 1 - x)`. */
    fun fadeOut(shape: FadeShape, x: Double): Double = fadeIn(shape, 1.0 - clamp01(x))

    // ---- dB helpers -------------------------------------------------------------------------------------

    fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)
    fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

    /** 20 log10(|lin|); values at or below zero (or below the floor) return [floorDb]. */
    fun linearToDb(lin: Double, floorDb: Double = -160.0): Double {
        val a = if (lin < 0) -lin else lin
        if (a <= 0.0) return floorDb
        val db = 20.0 * log10(a)
        return if (db < floorDb) floorDb else db
    }
    fun linearToDb(lin: Float, floorDb: Float = -160f): Float = linearToDb(lin.toDouble(), floorDb.toDouble()).toFloat()

    // ---- ramps ------------------------------------------------------------------------------------------

    /**
     * Fills `out[0 until n]` with gains going from [from] to [to] along [shape]: `out[i] = from + (to - from) *
     * fadeIn(shape, t_i)`. With [inclusiveEnd] (default) `t_i = i / (n - 1)` so both endpoints are hit exactly;
     * with false `t_i = i / n`, which is what a block-by-block ramp wants (the next block starts at [to]).
     * Returns [out] (allocated when null).
     */
    fun ramp(n: Int, from: Float, to: Float, shape: FadeShape = FadeShape.LINEAR, out: FloatArray? = null, inclusiveEnd: Boolean = true): FloatArray {
        val dst = out ?: FloatArray(n)
        require(dst.size >= n) { "out too small" }
        if (n <= 0) return dst
        if (n == 1) { dst[0] = if (inclusiveEnd) to else from; return dst }
        val denom = if (inclusiveEnd) (n - 1).toDouble() else n.toDouble()
        val span = (to - from).toDouble()
        when (shape) {
            FadeShape.LINEAR -> for (i in 0 until n) dst[i] = (from + span * (i / denom)).toFloat()
            else -> for (i in 0 until n) dst[i] = (from + span * fadeIn(shape, i / denom)).toFloat()
        }
        return dst
    }

    /**
     * Multiplies `channel[start until end]` in place by a gain ramp from [startGain] to [endGain] along [shape]
     * (block convention: sample i gets `t = i / len`, so consecutive ramps chain seamlessly).
     */
    fun applyGainRamp(channel: FloatArray, startGain: Float, endGain: Float, shape: FadeShape = FadeShape.LINEAR, start: Int = 0, end: Int = channel.size) {
        val len = end - start
        if (len <= 0) return
        val inv = 1.0 / len
        val span = (endGain - startGain).toDouble()
        if (shape == FadeShape.LINEAR) {
            val step = (span * inv).toFloat()
            var g = startGain
            for (i in start until end) { channel[i] *= g; g += step }
        } else {
            for (i in start until end) channel[i] *= (startGain + span * fadeIn(shape, (i - start) * inv)).toFloat()
        }
    }

    /** Applies a constant [gain] in place. */
    fun applyGain(channel: FloatArray, gain: Float, start: Int = 0, end: Int = channel.size) {
        for (i in start until end) channel[i] *= gain
    }

    /** `dst[i] += src[i] * gain` over [n] samples (offsets into each array). */
    fun mixAdd(dst: FloatArray, src: FloatArray, gain: Float, n: Int = minOf(dst.size, src.size), dstOffset: Int = 0, srcOffset: Int = 0) {
        for (i in 0 until n) dst[dstOffset + i] += src[srcOffset + i] * gain
    }

    /** `dst[i] += src[i] * g_i` with `g_i` ramping from [startGain] to [endGain] (block convention `t = i / n`). */
    fun mixAddRamp(dst: FloatArray, src: FloatArray, startGain: Float, endGain: Float, shape: FadeShape = FadeShape.LINEAR, n: Int = minOf(dst.size, src.size), dstOffset: Int = 0, srcOffset: Int = 0) {
        if (n <= 0) return
        val inv = 1.0 / n
        val span = (endGain - startGain).toDouble()
        if (shape == FadeShape.LINEAR) {
            val step = (span * inv).toFloat()
            var g = startGain
            for (i in 0 until n) { dst[dstOffset + i] += src[srcOffset + i] * g; g += step }
        } else {
            for (i in 0 until n) dst[dstOffset + i] += src[srcOffset + i] * (startGain + span * fadeIn(shape, i * inv)).toFloat()
        }
    }

    /**
     * Writes a crossfade of [a] (fading out) and [b] (fading in) into [dst] over [n] samples with [shape],
     * `x` running from [xStart] to [xEnd] (block convention). Lets a long crossfade be rendered block by block.
     */
    fun crossfade(dst: FloatArray, a: FloatArray, b: FloatArray, n: Int, shape: FadeShape, xStart: Double = 0.0, xEnd: Double = 1.0) {
        if (n <= 0) return
        val dx = (xEnd - xStart) / n
        var x = xStart
        for (i in 0 until n) {
            dst[i] = (a[i] * fadeOut(shape, x) + b[i] * fadeIn(shape, x)).toFloat()
            x += dx
        }
    }
}

/** One breakpoint of a [Lane]: value at [x] and the shape used to reach the *next* point. */
data class LanePoint(val x: Double, val value: Float, val shapeToNext: FadeShape = FadeShape.LINEAR)

/**
 * Automation lane / envelope: a sorted list of breakpoints with per-segment fade shapes, sampled at any x.
 * Before the first point the lane holds the first value, after the last point the last value; between points
 * `value = v0 + (v1 - v0) * fadeIn(shape, (x - x0) / (x1 - x0))`. x is whatever unit the caller uses (seconds,
 * beats, frames). Lookup is a binary search; [fill] renders a per-sample run without allocation.
 */
class Lane(points: List<LanePoint>) {
    val points: List<LanePoint> = points.sortedBy { it.x }
    init { require(points.isNotEmpty()) { "a lane needs at least one point" } }

    private val xs = DoubleArray(this.points.size) { this.points[it].x }

    val startX: Double get() = xs[0]
    val endX: Double get() = xs[xs.size - 1]

    /** Value of the lane at [x]. */
    fun sample(x: Double): Float {
        val pts = points
        if (x <= xs[0]) return pts[0].value
        val last = xs.size - 1
        if (x >= xs[last]) return pts[last].value
        // binary search: largest i with xs[i] <= x
        var lo = 0; var hi = last
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (xs[mid] <= x) lo = mid else hi = mid
        }
        return segmentValue(lo, x)
    }

    private fun segmentValue(i: Int, x: Double): Float {
        val p0 = points[i]; val p1 = points[i + 1]
        val w = p1.x - p0.x
        if (w <= 0.0) return p1.value
        val t = (x - p0.x) / w
        return (p0.value + (p1.value - p0.value) * Curves.fadeIn(p0.shapeToNext, t)).toFloat()
    }

    /** Fills `out[0 until n]` with the lane sampled at `x0 + i * dx` (e.g. dx = 1 / sampleRate). */
    fun fill(out: FloatArray, n: Int, x0: Double, dx: Double) {
        for (i in 0 until n) out[i] = sample(x0 + i * dx)
    }

    companion object {
        /** Two-point lane from ([x0], [v0]) to ([x1], [v1]) with [shape]. */
        fun segment(x0: Double, v0: Float, x1: Double, v1: Float, shape: FadeShape = FadeShape.LINEAR): Lane =
            Lane(listOf(LanePoint(x0, v0, shape), LanePoint(x1, v1)))
    }
}

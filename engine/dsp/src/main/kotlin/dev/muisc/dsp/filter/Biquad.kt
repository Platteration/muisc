package dev.muisc.dsp.filter

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Normalised second-order section coefficients (a0 == 1):
 *
 *     H(z) = (b0 + b1 z^-1 + b2 z^-2) / (1 + a1 z^-1 + a2 z^-2)
 *
 * Immutable value object; create instances with the factories in [Biquad] (RBJ Audio-EQ-Cookbook designs)
 * or directly for custom designs.
 */
data class BiquadCoefficients(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {

    /** Complex frequency response H(e^{jw}) at [freqHz]; returns `[re, im]`. */
    fun response(freqHz: Double, sampleRate: Double): DoubleArray {
        val w = 2.0 * PI * freqHz / sampleRate
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        // e^{-jw} = c1 - j s1 ; e^{-2jw} = c2 - j s2
        val nr = b0 + b1 * c1 + b2 * c2
        val ni = -(b1 * s1 + b2 * s2)
        val dr = 1.0 + a1 * c1 + a2 * c2
        val di = -(a1 * s1 + a2 * s2)
        val den = dr * dr + di * di
        return doubleArrayOf((nr * dr + ni * di) / den, (ni * dr - nr * di) / den)
    }

    /** Analytic magnitude response in dB at [freqHz] (evaluates |H(e^{jw})| directly from the coefficients). */
    fun magnitudeDb(freqHz: Double, sampleRate: Double): Double {
        val w = 2.0 * PI * freqHz / sampleRate
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2
        val ni = b1 * s1 + b2 * s2
        val dr = 1.0 + a1 * c1 + a2 * c2
        val di = a1 * s1 + a2 * s2
        return 10.0 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    /** Phase response in radians at [freqHz]. */
    fun phaseRadians(freqHz: Double, sampleRate: Double): Double {
        val r = response(freqHz, sampleRate)
        return atan2(r[1], r[0])
    }

    /** True when both poles are inside the unit circle (Jury criterion for a 2nd-order denominator). */
    val isStable: Boolean get() = abs(a2) < 1.0 && abs(a1) < 1.0 + a2

    companion object {
        val IDENTITY = BiquadCoefficients(1.0, 0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * RBJ Audio-EQ-Cookbook biquad designs (Robert Bristow-Johnson, "Cookbook formulae for audio EQ biquad
 * filter coefficients"). All designs are bilinear transforms of the classical analog 2nd-order prototypes with
 * frequency pre-warping, so the LPF/HPF/APF sections at equal (f0, Q) satisfy LP + HP = AP exactly, which
 * [LinkwitzRiley] relies on.
 *
 * Parameters: `f0` centre / corner frequency in Hz (must be in (0, sampleRate/2)), `q` the quality factor
 * (1/sqrt(2) = Butterworth for LPF/HPF), gains in dB for the EQ types.
 */
object Biquad {
    const val BUTTERWORTH_Q: Double = 0.7071067811865476

    private fun prewarp(f0: Double, sampleRate: Double, q: Double): DoubleArray {
        require(f0 > 0.0 && f0 < sampleRate / 2) { "f0=$f0 must be in (0, ${sampleRate / 2})" }
        require(q > 0.0) { "Q must be positive" }
        val w0 = 2.0 * PI * f0 / sampleRate
        return doubleArrayOf(cos(w0), sin(w0) / (2.0 * q))
    }

    private fun norm(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
        BiquadCoefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    /** 2nd-order low-pass; -3 dB at [f0] when q = 1/sqrt(2). */
    fun lowPass(f0: Double, sampleRate: Double, q: Double = BUTTERWORTH_Q): BiquadCoefficients {
        val (c, alpha) = prewarp(f0, sampleRate, q)
        return norm((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    /** 2nd-order high-pass; -3 dB at [f0] when q = 1/sqrt(2). */
    fun highPass(f0: Double, sampleRate: Double, q: Double = BUTTERWORTH_Q): BiquadCoefficients {
        val (c, alpha) = prewarp(f0, sampleRate, q)
        return norm((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    /**
     * Band-pass centred at [f0]. With [constantSkirt] = false (default) the peak gain is 0 dB; with true the
     * skirt gain is constant and the peak gain equals Q.
     */
    fun bandPass(f0: Double, sampleRate: Double, q: Double, constantSkirt: Boolean = false): BiquadCoefficients {
        val (c, alpha) = prewarp(f0, sampleRate, q)
        val g = if (constantSkirt) q * alpha else alpha
        return norm(g, 0.0, -g, 1 + alpha, -2 * c, 1 - alpha)
    }

    /** Notch (band-reject) at [f0]; bandwidth set by Q. */
    fun notch(f0: Double, sampleRate: Double, q: Double): BiquadCoefficients {
        val (c, alpha) = prewarp(f0, sampleRate, q)
        return norm(1.0, -2 * c, 1.0, 1 + alpha, -2 * c, 1 - alpha)
    }

    /** 2nd-order all-pass: unity magnitude everywhere, phase -180 degrees at [f0]. */
    fun allPass(f0: Double, sampleRate: Double, q: Double = BUTTERWORTH_Q): BiquadCoefficients {
        val (c, alpha) = prewarp(f0, sampleRate, q)
        return norm(1 - alpha, -2 * c, 1 + alpha, 1 + alpha, -2 * c, 1 - alpha)
    }

    /** Peaking EQ (bell): [gainDb] at [f0], bandwidth set by Q, unity far away. */
    fun peakingEq(f0: Double, sampleRate: Double, q: Double, gainDb: Double): BiquadCoefficients {
        val (c, alpha) = prewarp(f0, sampleRate, q)
        val a = 10.0.pow(gainDb / 40.0)
        return norm(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
    }

    /**
     * Low shelf: [gainDb] below [f0] (reached at DC), unity far above; the shelf mid-point (gainDb/2) is at
     * [f0]. [q] = 1/sqrt(2) corresponds to the RBJ shelf slope S = 1 (steepest slope without overshoot).
     */
    fun lowShelf(f0: Double, sampleRate: Double, gainDb: Double, q: Double = BUTTERWORTH_Q): BiquadCoefficients {
        val (c, alpha) = prewarp(f0, sampleRate, q)
        val a = 10.0.pow(gainDb / 40.0)
        val sa = 2 * sqrt(a) * alpha
        return norm(
            a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
            (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa,
        )
    }

    /** High shelf: [gainDb] above [f0] (reached at Nyquist), unity far below; see [lowShelf]. */
    fun highShelf(f0: Double, sampleRate: Double, gainDb: Double, q: Double = BUTTERWORTH_Q): BiquadCoefficients {
        val (c, alpha) = prewarp(f0, sampleRate, q)
        val a = 10.0.pow(gainDb / 40.0)
        val sa = 2 * sqrt(a) * alpha
        return norm(
            a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sa),
            (a + 1) - (a - 1) * c + sa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa,
        )
    }

    /** Linear interpolation of two coefficient sets: `a + t (b - a)`, t in [0, 1]. */
    fun interpolate(a: BiquadCoefficients, b: BiquadCoefficients, t: Double): BiquadCoefficients = BiquadCoefficients(
        a.b0 + t * (b.b0 - a.b0), a.b1 + t * (b.b1 - a.b1), a.b2 + t * (b.b2 - a.b2),
        a.a1 + t * (b.a1 - a.a1), a.a2 + t * (b.a2 - a.a2),
    )

    /** Convenience: analytic magnitude in dB of [c] at [freqHz]. */
    fun magnitudeDb(c: BiquadCoefficients, freqHz: Double, sampleRate: Double): Double = c.magnitudeDb(freqHz, sampleRate)
}

/**
 * Multi-channel biquad section in transposed direct form II (TDF-II) with double-precision state:
 *
 *     y  = b0 x + s1
 *     s1 = b1 x - a1 y + s2
 *     s2 = b2 x - a2 y
 *
 * Coefficients are shared across channels; each channel has its own (s1, s2). [setCoefficients] schedules a
 * new set that is reached by linear per-sample interpolation over the *next* processed block (zipper-noise
 * free, see [Biquad.interpolate]); pass `immediate = true` to switch instantly. Allocation-free after
 * construction.
 */
class BiquadFilter(val channels: Int, initial: BiquadCoefficients = BiquadCoefficients.IDENTITY) {
    init { require(channels > 0) { "channels must be positive" } }

    private val s1 = DoubleArray(channels)
    private val s2 = DoubleArray(channels)

    /** Coefficients in effect at the start of the next block. */
    var coefficients: BiquadCoefficients = initial
        private set
    private var target: BiquadCoefficients = initial
    private var pending = false

    /** The coefficients the filter will have reached after the next block. */
    val targetCoefficients: BiquadCoefficients get() = target

    /** Schedules [c]; with [immediate] = false the change is ramped linearly over the next block. */
    fun setCoefficients(c: BiquadCoefficients, immediate: Boolean = false) {
        target = c
        if (immediate) { coefficients = c; pending = false } else pending = c != coefficients
    }

    /** Clears the state (and completes any pending coefficient ramp). */
    fun reset() {
        s1.fill(0.0); s2.fill(0.0)
        coefficients = target; pending = false
    }

    /** Single-sample processing on [channel] using the current coefficients (no ramping). */
    fun processSample(x: Float, channel: Int = 0): Float {
        val c = coefficients
        val xd = x.toDouble()
        val y = c.b0 * xd + s1[channel]
        s1[channel] = c.b1 * xd - c.a1 * y + s2[channel]
        s2[channel] = c.b2 * xd - c.a2 * y
        return y.toFloat()
    }

    /** Filters [frames] frames of every channel from [input] into [output] (the arrays may be the same). */
    fun process(input: Array<FloatArray>, output: Array<FloatArray>, frames: Int) {
        for (ch in 0 until channels) processChannel(input[ch], output[ch], frames, ch)
        finishBlock()
    }

    fun processInPlace(buffer: Array<FloatArray>, frames: Int = buffer[0].size) = process(buffer, buffer, frames)

    /** Mono convenience: filters [frames] samples of [input] into [output] on channel 0. */
    fun process(input: FloatArray, output: FloatArray, frames: Int = input.size) {
        processChannel(input, output, frames, 0)
        finishBlock()
    }

    private fun finishBlock() {
        if (pending) { coefficients = target; pending = false }
    }

    private fun processChannel(x: FloatArray, y: FloatArray, frames: Int, ch: Int) {
        val c = coefficients
        var b0 = c.b0; var b1 = c.b1; var b2 = c.b2; var a1 = c.a1; var a2 = c.a2
        var st1 = s1[ch]; var st2 = s2[ch]
        if (pending && frames > 0) {
            val t = target
            val inv = 1.0 / frames
            val db0 = (t.b0 - b0) * inv; val db1 = (t.b1 - b1) * inv; val db2 = (t.b2 - b2) * inv
            val da1 = (t.a1 - a1) * inv; val da2 = (t.a2 - a2) * inv
            for (i in 0 until frames) {
                b0 += db0; b1 += db1; b2 += db2; a1 += da1; a2 += da2
                val xd = x[i].toDouble()
                val out = b0 * xd + st1
                st1 = b1 * xd - a1 * out + st2
                st2 = b2 * xd - a2 * out
                y[i] = out.toFloat()
            }
        } else {
            for (i in 0 until frames) {
                val xd = x[i].toDouble()
                val out = b0 * xd + st1
                st1 = b1 * xd - a1 * out + st2
                st2 = b2 * xd - a2 * out
                y[i] = out.toFloat()
            }
        }
        s1[ch] = st1; s2[ch] = st2
    }

    fun magnitudeDb(freqHz: Double, sampleRate: Double): Double = coefficients.magnitudeDb(freqHz, sampleRate)
}

/**
 * A chain of [stages] biquad sections applied in series (e.g. a 4th-order Butterworth = 2 sections, or an EQ
 * with several bands). Each stage keeps its own coefficients and ramping; the overall magnitude response is the
 * sum of the stage responses in dB.
 */
class BiquadCascade(val channels: Int, val stages: Int) {
    init { require(stages > 0) { "stages must be positive" } }

    val sections: Array<BiquadFilter> = Array(stages) { BiquadFilter(channels) }

    constructor(channels: Int, coefficients: List<BiquadCoefficients>) : this(channels, coefficients.size) {
        for (i in coefficients.indices) sections[i].setCoefficients(coefficients[i], immediate = true)
    }

    fun setCoefficients(stage: Int, c: BiquadCoefficients, immediate: Boolean = false) = sections[stage].setCoefficients(c, immediate)

    /** Sets every stage to the same coefficients (e.g. LR4 = two identical Butterworth sections). */
    fun setAll(c: BiquadCoefficients, immediate: Boolean = false) { for (s in sections) s.setCoefficients(c, immediate) }

    fun reset() { for (s in sections) s.reset() }

    fun processSample(x: Float, channel: Int = 0): Float {
        var v = x
        for (s in sections) v = s.processSample(v, channel)
        return v
    }

    /** Runs all stages; the first stage reads [input], subsequent stages work in place on [output]. */
    fun process(input: Array<FloatArray>, output: Array<FloatArray>, frames: Int) {
        sections[0].process(input, output, frames)
        for (i in 1 until stages) sections[i].process(output, output, frames)
    }

    fun processInPlace(buffer: Array<FloatArray>, frames: Int = buffer[0].size) = process(buffer, buffer, frames)

    fun process(input: FloatArray, output: FloatArray, frames: Int = input.size) {
        sections[0].process(input, output, frames)
        for (i in 1 until stages) sections[i].process(output, output, frames)
    }

    fun magnitudeDb(freqHz: Double, sampleRate: Double): Double {
        var db = 0.0
        for (s in sections) db += s.magnitudeDb(freqHz, sampleRate)
        return db
    }

    /** Complex response `[re, im]` of the whole cascade. */
    fun response(freqHz: Double, sampleRate: Double): DoubleArray {
        var re = 1.0; var im = 0.0
        for (s in sections) {
            val r = s.coefficients.response(freqHz, sampleRate)
            val nre = re * r[0] - im * r[1]
            im = re * r[1] + im * r[0]
            re = nre
        }
        return doubleArrayOf(re, im)
    }
}

package dev.muisc.dsp.window

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** Parameter-free window shapes selectable by name (see [Window.of]). */
enum class WindowType { RECTANGULAR, HANN, SQRT_HANN, HAMMING, BLACKMAN_HARRIS }

/**
 * Classical analysis/synthesis window functions plus a constant-overlap-add (COLA) checker.
 *
 * Every generator returns a fresh `FloatArray` of length `n`. The `periodic` flag selects the DFT-even
 * ("periodic") variant, where the cosine argument is `2*pi*i/n` and the window is one sample short of
 * symmetric — the right choice for STFT analysis because it makes the COLA property exact at hops N/2, N/4,
 * ... — versus the symmetric variant (`2*pi*i/(n-1)`) used for FIR filter design.
 */
object Window {
    /** Rectangular (boxcar) window: all ones. */
    fun rectangular(n: Int): FloatArray {
        require(n > 0) { "window length must be positive" }
        return FloatArray(n) { 1f }
    }

    /** Hann (raised cosine) window `0.5 - 0.5*cos(2*pi*i/L)`, L = n (periodic) or n-1 (symmetric). */
    fun hann(n: Int, periodic: Boolean = true): FloatArray = cosineSum(n, periodic, 0.5, 0.5, 0.0, 0.0)

    /** Square root of the Hann window; `w^2` is COLA so it serves as analysis and synthesis window (WOLA). */
    fun sqrtHann(n: Int, periodic: Boolean = true): FloatArray {
        val w = hann(n, periodic)
        for (i in w.indices) w[i] = sqrt(w[i].coerceAtLeast(0f))
        return w
    }

    /** Hamming window `0.54 - 0.46*cos(2*pi*i/L)`. */
    fun hamming(n: Int, periodic: Boolean = true): FloatArray = cosineSum(n, periodic, 0.54, 0.46, 0.0, 0.0)

    /** 4-term Blackman–Harris window (a0 = 0.35875, a1 = 0.48829, a2 = 0.14128, a3 = 0.01168), ~92 dB sidelobes. */
    fun blackmanHarris(n: Int, periodic: Boolean = true): FloatArray =
        cosineSum(n, periodic, 0.35875, 0.48829, 0.14128, 0.01168)

    /**
     * Kaiser window `I0(beta * sqrt(1 - (2i/L - 1)^2)) / I0(beta)` with the zeroth-order modified Bessel function
     * evaluated by its power series ([besselI0]). Symmetric endpoints equal `1/I0(beta)`; the centre equals 1.
     * Default is symmetric (filter design); pass `periodic = true` for STFT use.
     */
    fun kaiser(n: Int, beta: Double, periodic: Boolean = false): FloatArray {
        require(n > 0) { "window length must be positive" }
        require(beta >= 0.0) { "beta must be >= 0, was $beta" }
        if (n == 1) return floatArrayOf(1f)
        val l = if (periodic) n.toDouble() else (n - 1).toDouble()
        val denom = besselI0(beta)
        return FloatArray(n) { i ->
            val r = 2.0 * i / l - 1.0
            val arg = 1.0 - r * r
            (besselI0(beta * sqrt(arg.coerceAtLeast(0.0))) / denom).toFloat()
        }
    }

    /**
     * Tukey (tapered cosine) window: cosine tapers of total fraction [alpha] at both ends, flat top in between.
     * `alpha = 0` is rectangular, `alpha = 1` is Hann (periodic or symmetric per [periodic]).
     */
    fun tukey(n: Int, alpha: Double, periodic: Boolean = true): FloatArray {
        require(n > 0) { "window length must be positive" }
        require(alpha in 0.0..1.0) { "alpha must be in [0,1], was $alpha" }
        if (alpha == 0.0 || n == 1) return rectangular(n)
        val l = if (periodic) n.toDouble() else (n - 1).toDouble()
        val taper = alpha * l / 2.0
        return FloatArray(n) { i ->
            val d = minOf(i.toDouble(), l - i) // distance to the nearer edge
            if (d < taper) (0.5 - 0.5 * cos(PI * d / taper)).toFloat() else 1f
        }
    }

    /** Builds a parameter-free window by [type]. */
    fun of(type: WindowType, n: Int, periodic: Boolean = true): FloatArray = when (type) {
        WindowType.RECTANGULAR -> rectangular(n)
        WindowType.HANN -> hann(n, periodic)
        WindowType.SQRT_HANN -> sqrtHann(n, periodic)
        WindowType.HAMMING -> hamming(n, periodic)
        WindowType.BLACKMAN_HARRIS -> blackmanHarris(n, periodic)
    }

    /**
     * Zeroth-order modified Bessel function of the first kind, `I0(x) = sum_k ((x/2)^k / k!)^2`, summed until
     * the term drops below 1e-12 relative. Accurate to ~1e-12 relative for |x| up to several hundred.
     */
    fun besselI0(x: Double): Double {
        val h = x / 2.0
        var sum = 1.0
        var term = 1.0
        var k = 1
        while (k < 1000) {
            val f = h / k
            term *= f * f
            sum += term
            if (term < sum * 1e-12) break
            k++
        }
        return sum
    }

    /**
     * Overlap-add sum of [window] at hop [hop]: returns `s` of length `hop` with
     * `s[i] = sum_m window[i + m*hop]` over all m with the index in range. The window is COLA at this hop iff `s`
     * is constant; for periodic Hann at hop n/2 the constant is 1, at hop n/4 it is 2.
     */
    fun overlapAddSum(window: FloatArray, hop: Int): FloatArray {
        require(hop in 1..window.size) { "hop must be in [1, ${window.size}], was $hop" }
        val s = FloatArray(hop)
        for (i in 0 until hop) {
            var acc = 0.0
            var j = i
            while (j < window.size) { acc += window[j]; j += hop }
            s[i] = acc.toFloat()
        }
        return s
    }

    /** Relative COLA deviation `(max - min) / mean` of [overlapAddSum]; 0 means exactly constant. */
    fun colaDeviation(window: FloatArray, hop: Int): Float {
        val s = overlapAddSum(window, hop)
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE; var mean = 0.0
        for (v in s) { if (v < mn) mn = v; if (v > mx) mx = v; mean += v }
        mean /= s.size
        if (mean <= 0.0) return Float.POSITIVE_INFINITY
        return ((mx - mn) / mean).toFloat()
    }

    /** True if [window] satisfies the constant-overlap-add condition at [hop] within relative tolerance [tol]. */
    fun isCola(window: FloatArray, hop: Int, tol: Float = 1e-3f): Boolean = abs(colaDeviation(window, hop)) <= tol

    /** Generalised cosine-sum window `a0 - a1 cos(2πi/L) + a2 cos(4πi/L) - a3 cos(6πi/L)`. */
    private fun cosineSum(n: Int, periodic: Boolean, a0: Double, a1: Double, a2: Double, a3: Double): FloatArray {
        require(n > 0) { "window length must be positive" }
        if (n == 1) return floatArrayOf(1f)
        val l = if (periodic) n.toDouble() else (n - 1).toDouble()
        return FloatArray(n) { i ->
            val t = 2.0 * PI * i / l
            (a0 - a1 * cos(t) + a2 * cos(2 * t) - a3 * cos(3 * t)).toFloat()
        }
    }
}

package dev.muisc.dsp.fft

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Iterative radix-2 Cooley–Tukey complex FFT (decimation in time) with precomputed twiddle factors and a
 * precomputed bit-reversal permutation.
 *
 * - [size] must be a power of two between 2 and 65536 (the guaranteed/tested range is 16..65536).
 * - [forward] computes `X[k] = sum_n x[n] * exp(-2*pi*i*k*n/N)` (no scaling).
 * - [inverse] computes `x[n] = (1/N) * sum_k X[k] * exp(+2*pi*i*k*n/N)`, i.e. it is scaled by 1/N so that
 *   `inverse(forward(x)) == x` up to float rounding.
 *
 * Both transforms operate in place on separate real/imaginary `FloatArray`s and allocate nothing after
 * construction. Twiddles are computed in double precision and stored as floats; the butterflies run in float.
 * Typical worst-case error for N = 1024 and unit-range input is ~1e-5 absolute.
 */
class Fft(val size: Int) {
    init {
        require(size >= 2 && size <= MAX_SIZE && (size and (size - 1)) == 0) {
            "FFT size must be a power of two in [2, $MAX_SIZE], was $size"
        }
    }

    private val log2n = Integer.numberOfTrailingZeros(size)
    private val bitrev = IntArray(size) { Integer.reverse(it) ushr (32 - log2n) }
    /** cos(2*pi*k/N) and sin(2*pi*k/N) for k in [0, N/2). */
    private val cosT = FloatArray(size / 2) { cos(2.0 * PI * it / size).toFloat() }
    private val sinT = FloatArray(size / 2) { sin(2.0 * PI * it / size).toFloat() }

    /** Forward transform in place (unscaled). Both arrays must have length [size]. */
    fun forward(re: FloatArray, im: FloatArray) = transform(re, im, -1f)

    /** Inverse transform in place, scaled by 1/N. Both arrays must have length [size]. */
    fun inverse(re: FloatArray, im: FloatArray) {
        transform(re, im, 1f)
        val s = 1f / size
        for (i in 0 until size) { re[i] *= s; im[i] *= s }
    }

    private fun transform(re: FloatArray, im: FloatArray, sign: Float) {
        require(re.size == size && im.size == size) { "arrays must have length $size (got ${re.size}, ${im.size})" }
        val n = size
        // Bit-reversal permutation.
        for (i in 0 until n) {
            val j = bitrev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        // Butterfly stages: len = 2, 4, ..., n.
        var len = 2
        while (len <= n) {
            val half = len ushr 1
            val step = n / len
            var start = 0
            while (start < n) {
                var k = 0
                for (j in 0 until half) {
                    val wr = cosT[k]
                    val wi = sign * sinT[k]
                    val a = start + j
                    val b = a + half
                    val br = re[b]; val bi = im[b]
                    val tr = br * wr - bi * wi
                    val ti = br * wi + bi * wr
                    val ar = re[a]; val ai = im[a]
                    re[b] = ar - tr; im[b] = ai - ti
                    re[a] = ar + tr; im[a] = ai + ti
                    k += step
                }
                start += len
            }
            len = len shl 1
        }
    }

    companion object {
        const val MAX_SIZE = 65536
    }
}

/**
 * FFT of real-valued input using the classic "two-for-one" packing trick: the N real samples are packed as
 * N/2 complex numbers `z[n] = x[2n] + i*x[2n+1]`, transformed with an N/2-point [Fft], and the even/odd
 * spectra are separated with one extra twiddle pass. Cost is roughly half of a full N-point complex FFT.
 *
 * - [size] N must be a power of two in [16, 65536].
 * - [forward] produces the [bins] = N/2 + 1 non-redundant bins k = 0..N/2 (unscaled, same convention as [Fft]).
 * - [inverse] takes N/2 + 1 bins and produces N real samples, scaled by 1/N (the imaginary parts of bin 0 and
 *   bin N/2 are ignored since they are zero for real signals).
 *
 * All methods write into caller-provided arrays and allocate nothing after construction.
 */
class RealFft(val size: Int) {
    init {
        require(size >= 16 && size <= Fft.MAX_SIZE && (size and (size - 1)) == 0) {
            "RealFft size must be a power of two in [16, ${Fft.MAX_SIZE}], was $size"
        }
    }

    /** Number of complex output bins, N/2 + 1. */
    val bins: Int = size / 2 + 1
    private val half = size / 2
    private val fft = Fft(half)
    private val zr = FloatArray(half)
    private val zi = FloatArray(half)
    private val twCos = FloatArray(half + 1) { cos(2.0 * PI * it / size).toFloat() }
    private val twSin = FloatArray(half + 1) { sin(2.0 * PI * it / size).toFloat() }

    /** Forward real FFT: `x` (length N) → `re`/`im` (length ≥ N/2 + 1). */
    fun forward(x: FloatArray, re: FloatArray, im: FloatArray) {
        require(x.size == size) { "input must have length $size, was ${x.size}" }
        require(re.size >= bins && im.size >= bins) { "outputs must have length >= $bins" }
        for (n in 0 until half) { zr[n] = x[2 * n]; zi[n] = x[2 * n + 1] }
        fft.forward(zr, zi)
        val mask = half - 1
        for (k in 0..half) {
            val k1 = k and mask
            val k2 = (half - k) and mask
            val er = 0.5f * (zr[k1] + zr[k2])
            val ei = 0.5f * (zi[k1] - zi[k2])
            val or = 0.5f * (zi[k1] + zi[k2])
            val oi = -0.5f * (zr[k1] - zr[k2])
            val c = twCos[k]; val s = twSin[k]
            re[k] = er + (or * c + oi * s)
            im[k] = ei + (oi * c - or * s)
        }
    }

    /** Inverse real FFT: `re`/`im` (length ≥ N/2 + 1) → `x` (length N), scaled by 1/N. */
    fun inverse(re: FloatArray, im: FloatArray, x: FloatArray) {
        require(x.size == size) { "output must have length $size, was ${x.size}" }
        require(re.size >= bins && im.size >= bins) { "inputs must have length >= $bins" }
        for (k in 0 until half) {
            val k2 = half - k
            val er = 0.5f * (re[k] + re[k2])
            val ei = 0.5f * (im[k] - im[k2])
            val dr = 0.5f * (re[k] - re[k2])
            val di = 0.5f * (im[k] + im[k2])
            val c = twCos[k]; val s = twSin[k]
            val or = dr * c - di * s
            val oi = dr * s + di * c
            zr[k] = er - oi
            zi[k] = ei + or
        }
        fft.inverse(zr, zi)
        for (n in 0 until half) { x[2 * n] = zr[n]; x[2 * n + 1] = zi[n] }
    }

    /** Magnitude spectrum `|X[k]|` of the first [bins] bins into [out]. */
    fun magnitude(re: FloatArray, im: FloatArray, out: FloatArray) = magnitude(re, im, out, bins)

    /** Power spectrum `|X[k]|^2` of the first [bins] bins into [out]. */
    fun power(re: FloatArray, im: FloatArray, out: FloatArray) = power(re, im, out, bins)
}

/** Writes `sqrt(re[k]^2 + im[k]^2)` for k in [0, n) into [out]. */
fun magnitude(re: FloatArray, im: FloatArray, out: FloatArray, n: Int = out.size) {
    for (k in 0 until n) { val r = re[k]; val i = im[k]; out[k] = sqrt(r * r + i * i) }
}

/** Writes `re[k]^2 + im[k]^2` for k in [0, n) into [out]. */
fun power(re: FloatArray, im: FloatArray, out: FloatArray, n: Int = out.size) {
    for (k in 0 until n) { val r = re[k]; val i = im[k]; out[k] = r * r + i * i }
}

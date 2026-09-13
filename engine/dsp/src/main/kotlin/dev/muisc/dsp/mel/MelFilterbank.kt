package dev.muisc.dsp.mel

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * Triangular mel filterbank (Davis & Mermelstein 1980) for an [fftSize]-point real spectrum at [sampleRate].
 *
 * [nMels] filters are placed with centres equally spaced on the mel scale between [fMin] and [fMax] Hz. Filter
 * `m` is a triangle rising linearly from centre `m-1` to centre `m` and falling to centre `m+1` (the two extra
 * edge points are `fMin` and `fMax`), evaluated at the FFT bin frequencies `k * sampleRate / fftSize`.
 *
 * - [htk] = false (default) uses the Slaney/Auditory-Toolbox mel scale (linear below 1 kHz, logarithmic above,
 *   as in librosa's default); true uses the HTK formula `2595 * log10(1 + f/700)`.
 * - [normalize] = true applies Slaney area normalisation: each triangle is scaled by `2 / (f[m+1] - f[m-1])`
 *   so its area (in Hz) is 1. With `normalize = false` every filter peaks at 1 and adjacent filters sum to
 *   exactly 1 between the first and last centre frequencies.
 *
 * [apply] computes `out[m] = sum_k weights[m][k] * power[k]` over the non-zero support of each filter only and
 * allocates nothing.
 */
class MelFilterbank(
    val fftSize: Int,
    val sampleRate: Int,
    val nMels: Int,
    val fMin: Double = 0.0,
    val fMax: Double = sampleRate / 2.0,
    val normalize: Boolean = true,
    val htk: Boolean = false,
) {
    init {
        require(fftSize > 1 && sampleRate > 0 && nMels > 0) { "fftSize, sampleRate and nMels must be positive" }
        require(fMin >= 0.0 && fMax > fMin && fMax <= sampleRate / 2.0) { "need 0 <= fMin < fMax <= Nyquist (fMin=$fMin, fMax=$fMax)" }
    }

    /** Number of spectrum bins consumed, `fftSize/2 + 1`. */
    val bins: Int = fftSize / 2 + 1

    /** The `nMels + 2` mel-spaced edge frequencies in Hz (edge `m+1` is the centre of filter `m`). */
    val edgeFrequencies: DoubleArray = run {
        val mMin = hzToMel(fMin, htk); val mMax = hzToMel(fMax, htk)
        DoubleArray(nMels + 2) { melToHz(mMin + (mMax - mMin) * it / (nMels + 1), htk) }
    }

    /** Centre frequency in Hz of each filter. */
    val centerFrequencies: DoubleArray = DoubleArray(nMels) { edgeFrequencies[it + 1] }

    /** Dense weight matrix `[mel][bin]`. */
    val weights: Array<FloatArray>
    private val binStart = IntArray(nMels)
    private val binEnd = IntArray(nMels) // exclusive

    init {
        val binHz = sampleRate.toDouble() / fftSize
        weights = Array(nMels) { m ->
            val lo = edgeFrequencies[m]; val c = edgeFrequencies[m + 1]; val hi = edgeFrequencies[m + 2]
            val norm = if (normalize) 2.0 / (hi - lo) else 1.0
            val w = FloatArray(bins)
            var first = -1; var last = -1
            for (k in 0 until bins) {
                val f = k * binHz
                val up = (f - lo) / (c - lo)
                val down = (hi - f) / (hi - c)
                val v = minOf(up, down)
                if (v > 0.0) {
                    w[k] = (v * norm).toFloat()
                    if (first < 0) first = k
                    last = k
                }
            }
            binStart[m] = if (first < 0) 0 else first
            binEnd[m] = if (first < 0) 0 else last + 1
            w
        }
    }

    /** True if filter [m] covers no FFT bin at all (too narrow for this FFT size); its output is always 0. */
    fun isEmpty(m: Int): Boolean = binEnd[m] == binStart[m]

    /** Applies the filterbank to one power (or magnitude) spectrum (length ≥ [bins]) writing [nMels] band energies to [out]. */
    fun apply(powerSpectrum: FloatArray, out: FloatArray): FloatArray {
        require(powerSpectrum.size >= bins) { "spectrum must have >= $bins bins" }
        require(out.size >= nMels) { "out must have >= $nMels entries" }
        for (m in 0 until nMels) {
            val w = weights[m]
            var acc = 0f
            for (k in binStart[m] until binEnd[m]) acc += w[k] * powerSpectrum[k]
            out[m] = acc
        }
        return out
    }

    /** Applies the filterbank to every frame of a `[frame][bin]` spectrogram, returning `[frame][mel]`. */
    fun apply(powerFrames: Array<FloatArray>): Array<FloatArray> = Array(powerFrames.size) { apply(powerFrames[it], FloatArray(nMels)) }

    companion object {
        private const val F_SP = 200.0 / 3.0          // Slaney: Hz per mel below the break
        private const val MIN_LOG_HZ = 1000.0
        private const val MIN_LOG_MEL = MIN_LOG_HZ / F_SP
        private val LOG_STEP = ln(6.4) / 27.0

        /** Hz → mel. Slaney scale by default (linear below 1 kHz), HTK `2595*log10(1+f/700)` if [htk]. */
        fun hzToMel(hz: Double, htk: Boolean = false): Double {
            if (htk) return 2595.0 * log10(1.0 + hz / 700.0)
            return if (hz < MIN_LOG_HZ) hz / F_SP else MIN_LOG_MEL + ln(hz / MIN_LOG_HZ) / LOG_STEP
        }

        /** mel → Hz, inverse of [hzToMel]. */
        fun melToHz(mel: Double, htk: Boolean = false): Double {
            if (htk) return 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
            return if (mel < MIN_LOG_MEL) mel * F_SP else MIN_LOG_HZ * exp(LOG_STEP * (mel - MIN_LOG_MEL))
        }
    }
}

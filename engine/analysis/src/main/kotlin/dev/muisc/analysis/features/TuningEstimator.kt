package dev.muisc.analysis.features

import dev.muisc.audio.AudioBuffer
import dev.muisc.dsp.resample.Resampler
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Tuning deviation of a track from A4 = 440 Hz.
 *
 * [cents] is in `[-50, 50)` (positive = sharp; 0 for silence). [confidence] is 0..1: the share of peak
 * weight found within ±[TuningEstimator.meanRadiusCents] of the estimate, rescaled so that a flat histogram
 * (noise, or no consistent tuning) reads 0 and a perfectly consistent one reads 1.
 */
data class TuningEstimate(val cents: Float, val confidence: Float) {
    companion object { val NONE = TuningEstimate(0f, 0f) }
}

/**
 * Estimates the tuning offset from a histogram of spectral-peak frequencies folded to their distance from
 * the nearest equal-tempered semitone (the classic method of e.g. Dressler & Streich 2007 / librosa
 * `estimate_tuning`): every peak with `minFreqHz <= f <= maxFreqHz` contributes its magnitude to the 1-cent
 * bin `dev = 1200 log2(f / 440) mod 100`, wrapped to `[-50, 50)`. The histogram is smoothed with a circular
 * triangular kernel of ±[smoothCents] cents, the maximum is located and refined by a circular weighted mean
 * over ±[meanRadiusCents] cents around it.
 *
 * The frequency range excludes the kick / sub region (below 100 Hz, where drum pitch sweeps and the wide
 * relative bin spacing add noise) and the top octaves (above 4 kHz partials are weak and inharmonic).
 * Never throws: an empty peak list returns [TuningEstimate.NONE].
 */
class TuningEstimator(
    val minFreqHz: Double = 100.0,
    val maxFreqHz: Double = 4000.0,
    val smoothCents: Int = 5,
    val meanRadiusCents: Int = 10,
) {
    init { require(minFreqHz > 0 && maxFreqHz > minFreqHz && smoothCents >= 0 && meanRadiusCents in 1..49) }

    /** Estimate from an existing peak list (as produced by [SpectralPeakPicker]). */
    fun estimate(peaks: SpectralPeaks): TuningEstimate {
        if (peaks.isEmpty) return TuningEstimate.NONE
        val hist = DoubleArray(BINS)
        var total = 0.0
        val minF = minFreqHz; val maxF = maxFreqHz
        for (i in 0 until peaks.size) {
            val f = peaks.freqHz[i].toDouble()
            if (f < minF || f > maxF) continue
            val w = peaks.magnitude[i].toDouble()
            val cents = 1200.0 * ln(f / 440.0) / LN2
            var dev = cents - 100.0 * Math.rint(cents / 100.0) // [-50, 50]
            if (dev >= 50.0) dev -= 100.0
            val bin = Math.floorMod((dev + 50.0).toInt(), BINS)
            hist[bin] += w
            total += w
        }
        if (total <= 0.0) return TuningEstimate.NONE
        // Circular triangular smoothing.
        val smooth = DoubleArray(BINS)
        for (b in 0 until BINS) {
            var s = 0.0
            for (d in -smoothCents..smoothCents) s += hist[Math.floorMod(b + d, BINS)] * (smoothCents + 1 - kotlin.math.abs(d))
            smooth[b] = s
        }
        var best = 0
        for (b in 1 until BINS) if (smooth[b] > smooth[best]) best = b
        // Circular weighted mean around the maximum.
        var num = 0.0; var den = 0.0
        for (d in -meanRadiusCents..meanRadiusCents) {
            val w = hist[Math.floorMod(best + d, BINS)]
            num += w * (best + d); den += w
        }
        val centre = if (den > 0.0) num / den else best.toDouble()
        var cents = centre - 50.0 // bin b holds deviations in [b - 50, b - 49)
        cents += 0.5 // bin centre
        while (cents >= 50.0) cents -= 100.0
        while (cents < -50.0) cents += 100.0
        val share = den / total
        val uniform = (2.0 * meanRadiusCents + 1) / BINS
        val confidence = ((share - uniform) / (1.0 - uniform)).coerceIn(0.0, 1.0)
        return TuningEstimate(cents.toFloat(), confidence.toFloat())
    }

    /** Convenience: picks peaks on the 22.05 kHz mono downmix of `[startFrame, endFrame)` and estimates. */
    fun estimate(
        audio: AudioBuffer,
        startFrame: Long = 0L,
        endFrame: Long = audio.frames.toLong(),
        picker: SpectralPeakPicker = SpectralPeakPicker(),
        resampler: Resampler = Resampler(),
    ): TuningEstimate = estimate(picker.analyze(FeatureRate.downmix(audio, startFrame, endFrame, resampler, picker.sampleRate)))

    companion object {
        private const val BINS = 100
        private val LN2 = ln(2.0)
        /** Rounds a cents value to the nearest integer (helper for tests / display). */
        fun roundCents(c: Float): Int = c.roundToInt()
    }
}

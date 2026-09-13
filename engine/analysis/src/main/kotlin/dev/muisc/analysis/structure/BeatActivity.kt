package dev.muisc.analysis.structure

import dev.muisc.analysis.model.BarFeatures
import dev.muisc.analysis.model.BeatGrid
import kotlin.math.pow

/**
 * Per-beat "what is going on" summary that section labelling, intro/outro classification and cue finding read:
 *
 * - [energy] 0..1: relative level of each beat, `min(1, rms / ref)` where `ref` is the 95th percentile of the
 *   per-*bar* RMS (so one loud transient bar does not squash the rest; the loudest bars sit at 1).
 * - [drums] 0..1: drum presence. Internal estimate: the beat's high-band spectral flux
 *   ([BeatSyncFeatures.hfFlux], mean positive dB step per frame in the mel bands above 1.5 kHz — hats, snares,
 *   claps) mapped linearly from [HF_FLUX_ABSENT] (→ 0) to [HF_FLUX_FULL] (→ 1). From [BarFeatures]:
 *   `percussiveness` mapped linearly from [PERC_ABSENT] (→ 0) to [PERC_FULL] (→ 1). Both are heuristics: strummed
 *   guitars or a busy vocal also produce high-band flux, so treat 0.35 ([DRUMS_ABSENT]) / 0.5 ([DRUMS_PRESENT]) as
 *   the working thresholds rather than the raw number as a probability.
 * - [vocals] 0..1: [BarFeatures.vocalActivity] per beat, or all zero when no bar features were given
 *   ([hasVocals] = false, so vocal-based labels are never produced from the internal path).
 * - [valid]: beats that carry real data (a beat without any STFT frame, e.g. the zero-length beat a rigid grid
 *   puts on the last frame, is invalid and excluded from every mean).
 *
 * Bar-indexed [BarFeatures] are spread to beats via [BeatGrid.barOfBeat]: beats before the first downbeat take bar
 * 0, beats past the last bar take the last bar. `BarFeatures.energy` is treated as a linear quantity (RMS or
 * power-like); if it contains negative values it is assumed to be dB and converted with `10^(x/20)` first.
 */
class BeatActivity(
    val energy: FloatArray,
    val drums: FloatArray,
    val vocals: FloatArray,
    val valid: BooleanArray,
    val hasVocals: Boolean,
    val source: Source,
) {
    /** Where the activity came from. */
    enum class Source { INTERNAL, BAR_FEATURES, NONE }

    val beatCount: Int get() = energy.size

    /** Mean of [energy] over valid beats in `[from, to)` (clamped); 0 when the range holds no valid beat. */
    fun meanEnergy(from: Int, to: Int): Float = mean(energy, from, to)
    fun meanDrums(from: Int, to: Int): Float = mean(drums, from, to)
    fun meanVocals(from: Int, to: Int): Float = mean(vocals, from, to)

    /** Number of valid beats in `[from, to)`. */
    fun validCount(from: Int, to: Int): Int {
        var c = 0
        for (k in from.coerceAtLeast(0) until to.coerceAtMost(beatCount)) if (valid[k]) c++
        return c
    }

    private fun mean(a: FloatArray, from: Int, to: Int): Float {
        var s = 0.0; var c = 0
        for (k in from.coerceAtLeast(0) until to.coerceAtMost(beatCount)) if (valid[k]) { s += a[k]; c++ }
        return if (c == 0) 0f else (s / c).toFloat()
    }

    /** Median [energy] over valid beats in `[from, to)`; 0 when empty. */
    fun medianEnergy(from: Int, to: Int): Float {
        val v = ArrayList<Float>()
        for (k in from.coerceAtLeast(0) until to.coerceAtMost(beatCount)) if (valid[k]) v.add(energy[k])
        if (v.isEmpty()) return 0f
        v.sort()
        return quantileSorted(v.toFloatArray(), 0.5f)
    }

    companion object {
        /** Drum presence at or above this value counts as "drums present". */
        const val DRUMS_PRESENT = 0.5f
        /** Drum presence below this value counts as "no drums". */
        const val DRUMS_ABSENT = 0.35f
        /** Vocal activity at or above this value counts as "vocals present". */
        const val VOCALS_PRESENT = 0.5f
        /** High-band flux (dB per band per frame) that maps to drums = 0 / drums = 1 on the internal path. */
        const val HF_FLUX_ABSENT = 0.15f
        const val HF_FLUX_FULL = 1.0f
        /** [BarFeatures.percussiveness] that maps to drums = 0 / drums = 1. */
        const val PERC_ABSENT = 0.1f
        const val PERC_FULL = 0.4f
        /** Percentile of per-bar RMS used as the energy reference (= 1.0). */
        const val ENERGY_REF_QUANTILE = 0.95f

        fun empty(beatCount: Int = 0): BeatActivity =
            BeatActivity(FloatArray(beatCount), FloatArray(beatCount), FloatArray(beatCount), BooleanArray(beatCount), false, Source.NONE)

        /** Internal path: energy from [BeatSyncFeatures.rms], drums from [BeatSyncFeatures.hfFlux], no vocals. */
        fun fromFeatures(features: BeatSyncFeatures, grid: BeatGrid): BeatActivity {
            val n = features.beatCount
            if (n == 0) return empty(0)
            val valid = BooleanArray(n) { features.isValid(it) }
            val rms = features.rms
            val energy = normaliseEnergy(rms, valid, grid)
            val drums = FloatArray(n) { if (valid[it]) ramp(features.hfFlux[it], HF_FLUX_ABSENT, HF_FLUX_FULL) else 0f }
            return BeatActivity(energy, drums, FloatArray(n), valid, false, Source.INTERNAL)
        }

        /**
         * Bar-feature path. Beats are all valid (bar features cover the whole grid); [features] only supplies the
         * validity mask when given so the two paths agree on which beats exist.
         */
        fun fromBarFeatures(bars: BarFeatures, grid: BeatGrid, features: BeatSyncFeatures? = null): BeatActivity {
            val n = grid.beatCount
            if (n == 0 || bars.barCount == 0) return features?.let { fromFeatures(it, grid) } ?: empty(n)
            val valid = BooleanArray(n) { features?.isValid(it) ?: true }
            val barOf = IntArray(n) { grid.barOfBeat(it).coerceIn(0, bars.barCount - 1) }
            val e = bars.energy
            val negative = e.any { it < 0f }
            val lin = FloatArray(n) { k -> val v = e[barOf[k]]; if (negative) 10.0.pow(v / 20.0).toFloat() else v.coerceAtLeast(0f) }
            val energy = normaliseEnergy(lin, valid, grid)
            val perc = bars.percussiveness
            val voc = bars.vocalActivity
            val drums = FloatArray(n) { k -> if (perc.size == bars.barCount) ramp(perc[barOf[k]], PERC_ABSENT, PERC_FULL) else 0f }
            val hasVocals = voc.size == bars.barCount
            val vocals = FloatArray(n) { k -> if (hasVocals) voc[barOf[k]].coerceIn(0f, 1f) else 0f }
            return BeatActivity(energy, drums, vocals, valid, hasVocals, Source.BAR_FEATURES)
        }

        /** `min(1, x / ref)` with `ref` the [ENERGY_REF_QUANTILE] quantile of per-bar means of [x] over valid beats. */
        internal fun normaliseEnergy(x: FloatArray, valid: BooleanArray, grid: BeatGrid): FloatArray {
            val n = x.size
            val out = FloatArray(n)
            if (n == 0) return out
            // per-bar mean of valid beats
            val barSum = HashMap<Int, DoubleArray>()
            for (k in 0 until n) if (valid[k]) {
                val acc = barSum.getOrPut(grid.barOfBeat(k)) { DoubleArray(2) }
                acc[0] += x[k]; acc[1] += 1.0
            }
            if (barSum.isEmpty()) return out
            val barMeans = FloatArray(barSum.size)
            var i = 0
            for (acc in barSum.values) barMeans[i++] = (acc[0] / acc[1]).toFloat()
            barMeans.sort()
            val ref = quantileSorted(barMeans, ENERGY_REF_QUANTILE)
            if (ref <= 1e-9f) return out
            for (k in 0 until n) if (valid[k]) out[k] = (x[k] / ref).coerceIn(0f, 1f)
            return out
        }

        internal fun ramp(x: Float, lo: Float, hi: Float): Float = ((x - lo) / (hi - lo)).coerceIn(0f, 1f)

        /** Linear-interpolated quantile of an ascending array. */
        internal fun quantileSorted(sorted: FloatArray, q: Float): Float {
            if (sorted.isEmpty()) return 0f
            val pos = q.coerceIn(0f, 1f) * (sorted.size - 1)
            val i = pos.toInt()
            val f = pos - i
            return if (i + 1 < sorted.size) sorted[i] + f * (sorted[i + 1] - sorted[i]) else sorted[i]
        }
    }
}

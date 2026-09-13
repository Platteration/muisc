package dev.muisc.analysis.structure

import dev.muisc.analysis.model.BeatGrid
import dev.muisc.analysis.model.Section
import dev.muisc.analysis.model.SectionLabel
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Structural segmentation of a track from beat-synchronous features.
 *
 * Pipeline (all positions are beat indices of the [BeatGrid]):
 * 1. [selfSimilarity]: cosine similarity between every pair of [BeatSyncFeatures.vectors] → `n × n` matrix in
 *    [-1, 1] (0 for invalid / all-zero beats).
 * 2. [novelty]: Foote (2000, "Automatic audio segmentation using a measure of audio novelty") checkerboard
 *    kernel correlated along the diagonal. The kernel of size `K` spans beats `t-K/2 .. t+K/2-1`, weights
 *    `sign(i)·sign(j)·g(i)·g(j)` with a Gaussian taper `g(i) = exp(-(i+½)² / 2σ²)`, `σ = K/4`; at the edges only
 *    in-range pairs are summed and the result is divided by the summed |weight| so the scale stays comparable.
 *    Negative values are clamped to 0 and each kernel's curve is normalised by its own maximum → 0..1.
 * 3. [combinedNovelty]: mean of the per-kernel curves for [kernelSizes] (16 and 64 beats by default: the short
 *    kernel catches 2–4-bar changes, the long one 8–16-bar section changes), 0..1.
 * 4. [pickBoundaries]: adaptive-threshold peak picking — a local maximum at `t` is a boundary when
 *    `c[t] ≥ minNovelty` and `c[t] > mean + thresholdK·std` over `t ± localWindowBeats`; candidates are accepted in
 *    descending order with a minimum spacing of [minDistanceBeats]. Each boundary is snapped to the nearest
 *    downbeat ([BeatGrid.nearestDownbeat]), duplicates removed, and 0 / `beatCount` are always present.
 * 5. [label]: sections get [SectionLabel]s from their mean [BeatActivity] (see the decision table there).
 *
 * `novelty[t]` is the evidence for a boundary *before* beat `t` (between `t-1` and `t`), so a section change on
 * beat 16 produces a peak at index 16.
 */
class StructureAnalyzer(
    val kernelSizes: IntArray = intArrayOf(16, 64),
    /** Peak threshold in local standard deviations above the local mean. */
    val thresholdK: Float = 1.0f,
    /** Half-width (beats) of the window for the local mean / std. */
    val localWindowBeats: Int = 32,
    /** Minimum spacing between accepted novelty peaks, in beats. */
    val minDistanceBeats: Int = 8,
    /** Absolute floor on the combined novelty (0..1) a peak must reach. */
    val minNovelty: Float = 0.15f,
) {
    /** Cosine self-similarity matrix of [features]`.vectors`, `[i][j]` in [-1, 1]. */
    fun selfSimilarity(features: BeatSyncFeatures): Array<FloatArray> {
        val n = features.beatCount
        val v = features.vectors
        val norm = FloatArray(n) { b -> var s = 0f; for (x in v[b]) s += x * x; sqrt(s) }
        val ssm = Array(n) { FloatArray(n) }
        for (i in 0 until n) {
            if (norm[i] <= 1e-9f) continue
            val vi = v[i]
            val row = ssm[i]
            row[i] = 1f
            for (j in i + 1 until n) {
                if (norm[j] <= 1e-9f) continue
                val vj = v[j]
                var d = 0f
                for (k in vi.indices) d += vi[k] * vj[k]
                val s = d / (norm[i] * norm[j])
                row[j] = s
                ssm[j][i] = s
            }
        }
        return ssm
    }

    /** Foote checkerboard novelty of [ssm] with a kernel of [kernelBeats] beats; clamped at 0 and normalised to max 1. */
    fun novelty(ssm: Array<FloatArray>, kernelBeats: Int): FloatArray {
        val n = ssm.size
        val out = FloatArray(n)
        if (n == 0 || kernelBeats < 2) return out
        val half = kernelBeats / 2
        val sigma = kernelBeats / 4.0
        val g = FloatArray(2 * half) { val d = (it - half) + 0.5; exp(-d * d / (2 * sigma * sigma)).toFloat() }
        var maxV = 0f
        for (t in 0 until n) {
            var acc = 0.0
            var wsum = 0.0
            val i0 = max(-half, -t); val i1 = min(half, n - t)
            for (i in i0 until i1) {
                val row = ssm[t + i]
                val gi = g[i + half]
                val negI = i < 0
                for (j in i0 until i1) {
                    val w = gi * g[j + half]
                    val s = row[t + j]
                    if (negI == (j < 0)) acc += w * s else acc -= w * s
                    wsum += w
                }
            }
            val v = if (wsum > 0) (acc / wsum).toFloat() else 0f
            out[t] = if (v > 0f) v else 0f
            if (out[t] > maxV) maxV = out[t]
        }
        if (maxV > 0f) for (t in 0 until n) out[t] /= maxV
        return out
    }

    /** Mean of the normalised per-kernel novelties, 0..1 per beat (empty for an empty matrix). */
    fun combinedNovelty(ssm: Array<FloatArray>): FloatArray {
        val n = ssm.size
        val out = FloatArray(n)
        if (n == 0 || kernelSizes.isEmpty()) return out
        for (k in kernelSizes) {
            val nov = novelty(ssm, k)
            for (t in 0 until n) out[t] += nov[t]
        }
        val inv = 1f / kernelSizes.size
        for (t in 0 until n) out[t] *= inv
        return out
    }

    /** Raw (un-snapped) peak indices of [novelty], ascending, following the rules in the class KDoc. */
    fun pickPeaks(novelty: FloatArray): IntArray {
        val n = novelty.size
        if (n < 3) return IntArray(0)
        val prefix = DoubleArray(n + 1)
        val prefix2 = DoubleArray(n + 1)
        for (t in 0 until n) { prefix[t + 1] = prefix[t] + novelty[t]; prefix2[t + 1] = prefix2[t] + novelty[t].toDouble() * novelty[t] }
        val candidates = ArrayList<Int>()
        for (t in 1 until n - 1) {
            val v = novelty[t]
            if (v < minNovelty) continue
            if (v < novelty[t - 1] || v < novelty[t + 1]) continue
            if (v == novelty[t - 1] && t > 1) continue // plateau: keep the first sample only
            val a = max(0, t - localWindowBeats); val b = min(n, t + localWindowBeats + 1)
            val cnt = (b - a).toDouble()
            val mean = (prefix[b] - prefix[a]) / cnt
            val varr = max(0.0, (prefix2[b] - prefix2[a]) / cnt - mean * mean)
            if (v > mean + thresholdK * sqrt(varr)) candidates.add(t)
        }
        candidates.sortByDescending { novelty[it] }
        val accepted = ArrayList<Int>()
        for (c in candidates) {
            if (accepted.none { kotlin.math.abs(it - c) < minDistanceBeats }) accepted.add(c)
        }
        accepted.sort()
        return accepted.toIntArray()
    }

    /**
     * Section boundaries as ascending beat indices: `0`, the novelty peaks snapped to the nearest downbeat (kept
     * only when strictly inside `(0, beatCount)`), and `beatCount`. Empty for an empty grid.
     */
    fun pickBoundaries(novelty: FloatArray, grid: BeatGrid): IntArray {
        val n = grid.beatCount
        if (n == 0) return IntArray(0)
        val set = java.util.TreeSet<Int>()
        set.add(0); set.add(n)
        for (p in pickPeaks(novelty)) {
            val d = grid.nearestDownbeat(p.toDouble())
            if (d in 1 until n) set.add(d)
        }
        return set.toIntArray()
    }

    /**
     * Labels the sections delimited by [boundaries] (ascending, first = 0, last = beat count) from [activity].
     *
     * Per section: `energy` = mean [BeatActivity.energy], `drums` = mean [BeatActivity.drums], `vocals` = mean
     * [BeatActivity.vocals] over its valid beats. With `maxE` the largest section energy, `q75` the 75th percentile
     * of section energies and `prev` / `next` the neighbouring sections, the rules are applied in this order:
     *
     * | label     | condition                                                                                   |
     * |-----------|---------------------------------------------------------------------------------------------|
     * | INTRO     | first section, ≥ 2 sections, and (energy < [LOW_ENERGY_FRAC]·maxE or drums < DRUMS_ABSENT)   |
     * | OUTRO     | last section, ≥ 2 sections, and (energy < [OUTRO_DROP_FRAC]·prev.energy or energy < LOW·maxE or drums < DRUMS_ABSENT with prev drums ≥ DRUMS_ABSENT) |
     * | DROP      | energy ≥ q75, drums ≥ DRUMS_ABSENT and energy − prev.energy ≥ [DROP_JUMP]                     |
     * | CHORUS    | energy ≥ q75 (and not DROP)                                                                  |
     * | BREAKDOWN | not first/last, energy < LOW·maxE and drums < DRUMS_ABSENT                                   |
     * | BUILD     | energy rises by ≥ [BUILD_RISE] from its first to its last quarter and next is DROP/CHORUS    |
     * | VERSE     | everything else                                                                              |
     *
     * BRIDGE is never emitted (no reliable harmonic-contrast cue without a key per section). A single-section
     * track is VERSE (or INTRO-less / OUTRO-less by definition).
     */
    fun label(boundaries: IntArray, activity: BeatActivity): List<Section> {
        val count = boundaries.size - 1
        if (count <= 0) return emptyList()
        val starts = IntArray(count) { boundaries[it] }
        val ends = IntArray(count) { boundaries[it + 1] }
        val energy = FloatArray(count) { activity.meanEnergy(starts[it], ends[it]) }
        val drums = FloatArray(count) { activity.meanDrums(starts[it], ends[it]) }
        val vocals = FloatArray(count) { activity.meanVocals(starts[it], ends[it]) }
        val maxE = energy.maxOrNull() ?: 0f
        val q75 = BeatActivity.quantileSorted(energy.copyOf().also { it.sort() }, TOP_QUANTILE)
        val isTop = BooleanArray(count) { energy[it] >= q75 && maxE > 0f }
        val labels = Array(count) { SectionLabel.VERSE }
        for (i in 0 until count) {
            val e = energy[i]; val d = drums[i]
            val prevE = if (i > 0) energy[i - 1] else e
            val prevD = if (i > 0) drums[i - 1] else d
            labels[i] = when {
                i == 0 && count >= 2 && (e < LOW_ENERGY_FRAC * maxE || d < BeatActivity.DRUMS_ABSENT) -> SectionLabel.INTRO
                i == count - 1 && count >= 2 && (e < OUTRO_DROP_FRAC * prevE || e < LOW_ENERGY_FRAC * maxE ||
                    (d < BeatActivity.DRUMS_ABSENT && prevD >= BeatActivity.DRUMS_ABSENT)) -> SectionLabel.OUTRO
                isTop[i] && d >= BeatActivity.DRUMS_ABSENT && i > 0 && e - prevE >= DROP_JUMP -> SectionLabel.DROP
                isTop[i] -> SectionLabel.CHORUS
                i in 1 until count - 1 && e < LOW_ENERGY_FRAC * maxE && d < BeatActivity.DRUMS_ABSENT -> SectionLabel.BREAKDOWN
                i < count - 1 && isTop[i + 1] && rises(activity, starts[i], ends[i]) -> SectionLabel.BUILD
                else -> SectionLabel.VERSE
            }
        }
        return List(count) { Section(starts[it], ends[it], labels[it], energy[it], drums[it], vocals[it]) }
    }

    /** Whether the mean energy of the last quarter of `[from, to)` exceeds the first quarter's by ≥ [BUILD_RISE]. */
    private fun rises(activity: BeatActivity, from: Int, to: Int): Boolean {
        val len = to - from
        if (len < 4) return false
        val q = len / 4
        return activity.meanEnergy(to - q, to) - activity.meanEnergy(from, from + q) >= BUILD_RISE
    }

    /** Full pipeline: SSM → novelty → boundaries → labelled sections. Empty features give an empty list. */
    fun analyze(features: BeatSyncFeatures, grid: BeatGrid, activity: BeatActivity): List<Section> {
        if (features.beatCount == 0 || grid.isEmpty) return emptyList()
        val ssm = selfSimilarity(features)
        val nov = combinedNovelty(ssm)
        return label(pickBoundaries(nov, grid), activity)
    }

    companion object {
        /** A section below this fraction of the loudest section is "low energy". */
        const val LOW_ENERGY_FRAC = 0.6f
        /** The last section is an OUTRO when its energy drops below this fraction of the previous section's. */
        const val OUTRO_DROP_FRAC = 0.8f
        /** Section-energy quantile that marks the DROP / CHORUS tier. */
        const val TOP_QUANTILE = 0.75f
        /** Energy step from the previous section that turns a top-tier section into a DROP. */
        const val DROP_JUMP = 0.2f
        /** Energy rise (last quarter − first quarter of the section) that marks a BUILD. */
        const val BUILD_RISE = 0.15f
    }
}

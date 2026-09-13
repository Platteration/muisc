package dev.muisc.transitions.planner

import dev.muisc.analysis.model.TrackAnalysis
import dev.muisc.dsp.stems.StemQuality
import dev.muisc.transitions.PairFeatures
import dev.muisc.transitions.TempoRelation
import dev.muisc.transitions.TransitionPrefs
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * The eight compatibility sub-scores of DESIGN.md §5.2 for one strategy on one pair, all in 0..1.
 * [summary] is the one-line form the Lab / CLI print ("tempo 0.82 · key 0.85 · energy 0.90 …").
 */
data class SubScores(
    val tempo: Double,
    val key: Double,
    val energy: Double,
    val vocal: Double,
    val grid: Double,
    val struct: Double,
    val room: Double,
    val stems: Double,
) {
    /** Weighted sum `Σ w_i · s_i` (weights need not sum to 1; the result is clamped to 0..1). */
    fun weighted(
        wTempo: Double = 0.0, wKey: Double = 0.0, wEnergy: Double = 0.0, wVocal: Double = 0.0,
        wGrid: Double = 0.0, wStruct: Double = 0.0, wRoom: Double = 0.0, wStems: Double = 0.0,
    ): Double = (wTempo * tempo + wKey * key + wEnergy * energy + wVocal * vocal + wGrid * grid + wStruct * struct + wRoom * room + wStems * stems).coerceIn(0.0, 1.0)

    /** Sub-scores by name in a stable order (for JSON / CSV). */
    fun asMap(): Map<String, Double> = linkedMapOf(
        "tempo" to tempo, "key" to key, "energy" to energy, "vocal" to vocal, "grid" to grid, "struct" to struct, "room" to room, "stems" to stems,
    )

    /** `"tempo 0.82 · key 0.85 · energy 0.90 · vocal 1.00 · grid 0.95 · struct 0.70 · room 1.00 · stems 0.80"`. */
    fun summary(): String = asMap().entries.joinToString(" · ") { (k, v) -> "$k ${"%.2f".format(v)}" }

    init {
        for ((k, v) in asMap()) require(v in 0.0..1.0) { "$k = $v is not in 0..1" }
    }
}

/**
 * Pure functions computing the §5.2 compatibility sub-scores from [PairFeatures] and [TransitionPrefs]:
 *
 * ```
 * s_tempo  = exp(−(stretchPercent / σ_t)²),  σ_t = prefs.maxStretchPercent / 1.5      (HALF/DOUBLE: × 0.85)
 * s_key    = {0: 1.0, 1: 0.85, 2: 0.5, 3: 0.35, ≥4: 0.1}[camelotDistanceAfterShift]  blended toward 0.6 by (1 − min(keyStrengthA, keyStrengthB))
 * s_energy = exp(−(loudnessDeltaLu / 6)²) · (1 − 0.5·|energyDelta|)
 * s_vocal  = 1 − vocalClash
 * s_grid   = min(gridConfidenceA, gridConfidenceB)
 * s_struct = StructTables prior of the strategy for (outro, intro)
 * s_room   = min(1, outroBeatsAvailable / needed) · min(1, introBeatsAvailable / needed)
 * s_stems  = 1.0 (ML) | 0.8 (PSEUDO) | 0 (needs stems, none available)      (1.0 for strategies that need none)
 * ```
 *
 * Strategies compute their own `fit` from these in `applicability()`; the planner recomputes them for the
 * explanation shown next to every candidate (see [PlanExplanation]).
 */
object CompatibilityScores {
    /** `s_key` base values by Camelot distance 0, 1, 2, 3, ≥ 4. */
    val KEY_BY_DISTANCE: DoubleArray = doubleArrayOf(1.0, 0.85, 0.5, 0.35, 0.1)

    /** Neutral value `s_key` is blended toward when the key estimates are weak. */
    const val KEY_NEUTRAL: Double = 0.6

    /** Penalty on `s_tempo` when the match is a half/double-time interpretation. */
    const val OCTAVE_TEMPO_FACTOR: Double = 0.85

    /** Loudness delta (LU) at which `s_energy` has fallen to 1/e. */
    const val LOUDNESS_SIGMA_LU: Double = 6.0

    const val STEMS_ML: Double = 1.0
    const val STEMS_PSEUDO: Double = 0.8

    /** `s_tempo`. With `maxStretchPercent ≤ 0` only an exact tempo match scores 1, everything else 0. */
    fun tempo(f: PairFeatures, prefs: TransitionPrefs): Double {
        val sigma = prefs.maxStretchPercent / 1.5
        val base = if (sigma <= 0.0) (if (f.stretchPercent <= 0.0) 1.0 else 0.0) else exp(-(f.stretchPercent / sigma).let { it * it })
        val octave = if (f.tempoRelation == TempoRelation.SAME) 1.0 else OCTAVE_TEMPO_FACTOR
        return (base * octave).coerceIn(0.0, 1.0)
    }

    /** `s_key` from the Camelot distance after the best pitch shift, blended toward [KEY_NEUTRAL] by the weaker key strength. */
    fun key(f: PairFeatures): Double {
        val d = f.camelotDistanceAfterShift.coerceAtLeast(0)
        val base = KEY_BY_DISTANCE[min(d, KEY_BY_DISTANCE.size - 1)]
        val confidence = min(f.keyStrengthA, f.keyStrengthB).coerceIn(0.0, 1.0)
        return (base + (KEY_NEUTRAL - base) * (1.0 - confidence)).coerceIn(0.0, 1.0)
    }

    /** `s_energy` from the loudness delta and the head/tail energy delta. */
    fun energy(f: PairFeatures): Double {
        val lu = f.loudnessDeltaLu / LOUDNESS_SIGMA_LU
        val level = exp(-(lu * lu))
        val dynamics = 1.0 - 0.5 * abs(f.energyDelta).coerceIn(0.0, 1.0)
        return (level * dynamics).coerceIn(0.0, 1.0)
    }

    /** `s_vocal = 1 − vocalClash`. */
    fun vocal(f: PairFeatures): Double = (1.0 - f.vocalClash).coerceIn(0.0, 1.0)

    /** `s_grid = min(gridConfidenceA, gridConfidenceB)`. */
    fun grid(f: PairFeatures): Double = min(f.gridConfidenceA, f.gridConfidenceB).coerceIn(0.0, 1.0)

    /** `s_struct` for a strategy id (family prior, see [StructTables]). */
    fun struct(strategyId: String, f: PairFeatures): Double = StructTables.prior(strategyId, f.outro, f.intro)

    /** `s_room` for a strategy needing [neededBeats] beats on each side (≤ 0 beats needed → 1). */
    fun room(f: PairFeatures, neededBeats: Int): Double {
        if (neededBeats <= 0) return 1.0
        val n = neededBeats.toDouble()
        val outro = min(1.0, f.outroBeatsAvailable.coerceAtLeast(0) / n)
        val intro = min(1.0, f.introBeatsAvailable.coerceAtLeast(0) / n)
        return outro * intro
    }

    /** `s_stems` for the available stem [quality] (null = no separator available). */
    fun stems(quality: StemQuality?): Double = when (quality) {
        StemQuality.ML -> STEMS_ML
        StemQuality.PSEUDO -> STEMS_PSEUDO
        null -> 0.0
    }

    /**
     * Beats a strategy of the given family wants on each side of the seam for `s_room`: beat-domain blends need
     * `prefs.preferredOverlapBars` bars, cut-style moves 8 bars, structural moves 4, bridges 2, crossfades 2
     * (at A's beats-per-bar, 4 when unknown).
     */
    fun neededBeats(strategyId: String, a: TrackAnalysis?, prefs: TransitionPrefs): Int {
        val bpb = a?.grid?.beatsPerBar?.takeIf { it >= 1 } ?: 4
        val bars = when (StructTables.family(strategyId)) {
            StrategyFamily.BEAT_DOMAIN -> prefs.preferredOverlapBars.coerceAtLeast(1)
            StrategyFamily.CUT -> 8
            StrategyFamily.STRUCTURAL -> 4
            StrategyFamily.BRIDGE, StrategyFamily.CROSSFADE -> 2
            StrategyFamily.GENERIC -> 8
        }
        return bars * bpb
    }

    /**
     * All sub-scores of [strategyId] for a pair. [stemQuality] is the best separator available; `s_stems` is
     * 1.0 for strategies outside [StructTables.STEM_STRATEGY_IDS].
     */
    fun compute(
        strategyId: String, f: PairFeatures, prefs: TransitionPrefs, a: TrackAnalysis? = null,
        stemQuality: StemQuality? = StemQuality.PSEUDO, neededBeats: Int = neededBeats(strategyId, a, prefs),
    ): SubScores = SubScores(
        tempo = tempo(f, prefs),
        key = key(f),
        energy = energy(f),
        vocal = vocal(f),
        grid = grid(f),
        struct = struct(strategyId, f),
        room = room(f, neededBeats),
        stems = if (strategyId in StructTables.STEM_STRATEGY_IDS) stems(stemQuality) else 1.0,
    )
}

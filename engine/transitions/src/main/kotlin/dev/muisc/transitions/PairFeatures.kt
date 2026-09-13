package dev.muisc.transitions

import dev.muisc.analysis.model.IntroType
import dev.muisc.analysis.model.OutroType
import kotlinx.serialization.Serializable

/** How B's tempo relates to A's after choosing the best half/double-time interpretation. */
enum class TempoRelation { SAME, HALF, DOUBLE }

/**
 * Everything the planner and strategies need to know about a PAIR, derived from the two analyses.
 * Computed by [PairAnalyzer]; strategies never recompute these.
 */
@Serializable
data class PairFeatures(
    /** B.bpm / A.bpm after half/double-time selection (close to 1.0 means beat-matchable). */
    val tempoRatio: Double,
    val tempoRelation: TempoRelation,
    /** Percentage stretch needed to match tempos symmetrically (|ratio-1|*100 with the closest relation). */
    val stretchPercent: Double,
    /** Camelot distance 0..6 between the two keys (0 = identical). */
    val camelotDistance: Int,
    /** Semitone shift (-6..6) of B that minimises Camelot distance; 0 when already compatible. */
    val bestPitchShiftSemitones: Int,
    /** Camelot distance after applying [bestPitchShiftSemitones]. */
    val camelotDistanceAfterShift: Int,
    /** B.integratedLufs - A.integratedLufs in LU (positive = B louder). */
    val loudnessDeltaLu: Double,
    /** Energy of B's head relative to A's tail, -1..1. */
    val energyDelta: Double,
    /** 0..1: how much both A's tail and B's head carry vocals simultaneously (higher = clash risk). */
    val vocalClash: Double,
    /** 0..1 cosine similarity of the long-term spectra. */
    val spectralSimilarity: Double,
    val outro: OutroType,
    val intro: IntroType,
    /** Beats available for mixing at the end of A (from mixOutBeat to the last beat) and at the start of B. */
    val outroBeatsAvailable: Int,
    val introBeatsAvailable: Int,
    /** Grid confidences copied from the analyses (planner gates on these). */
    val gridConfidenceA: Double,
    val gridConfidenceB: Double,
    val keyStrengthA: Double,
    val keyStrengthB: Double,
    /** Sub+bass share of the energy in A's tail and B's head (0..1). */
    val lowEndShareA: Double,
    val lowEndShareB: Double,
) {
    val beatMatchable: Boolean get() = gridConfidenceA >= 0.5 && gridConfidenceB >= 0.5
}
